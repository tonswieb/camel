/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.reactive.streams.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.reactive.streams.ReactiveStreamsComponent;
import org.apache.camel.component.reactive.streams.ReactiveStreamsConsumer;
import org.apache.camel.component.reactive.streams.ReactiveStreamsProducer;
import org.apache.camel.component.reactive.streams.api.CamelReactiveStreamsService;
import org.apache.camel.component.reactive.streams.api.DispatchCallback;
import org.apache.camel.component.reactive.streams.util.ConvertingPublisher;
import org.apache.camel.component.reactive.streams.util.ConvertingSubscriber;
import org.apache.camel.component.reactive.streams.util.MonoPublisher;
import org.apache.camel.component.reactive.streams.util.UnwrapStreamProcessor;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.spi.Synchronization;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * The default implementation of the reactive streams service.
 */
public class CamelReactiveStreamsServiceImpl implements CamelReactiveStreamsService {

    private CamelContext context;

    private ExecutorService workerPool;

    private final Map<String, CamelPublisher> publishers = new ConcurrentHashMap<>();

    private final Map<String, CamelSubscriber> subscribers = new ConcurrentHashMap<>();

    private final Map<String, String> publishedUriToStream = new ConcurrentHashMap<>();

    private final Map<String, String> requestedUriToStream = new ConcurrentHashMap<>();

    public CamelReactiveStreamsServiceImpl() {
    }

    @Override
    public void start() throws Exception {
        ReactiveStreamsComponent component = context.getComponent("reactive-streams", ReactiveStreamsComponent.class);
        ReactiveStreamsEngineConfiguration config = component.getInternalEngineConfiguration();
        this.workerPool = context.getExecutorServiceManager().newThreadPool(this, config.getThreadPoolName(), config.getThreadPoolMinSize(), config.getThreadPoolMaxSize());
    }

    @Override
    public void stop() throws Exception {
        if (this.workerPool != null) {
            context.getExecutorServiceManager().shutdownNow(this.workerPool);
        }
    }

    @Override
    public Publisher<Exchange> getPublisher(String name) {
        return new UnwrappingPublisher<>(getPayloadPublisher(name));
    }

    @SuppressWarnings("unchecked")
    public <T> Publisher<T> getPublisher(String name, Class<T> cls) {
        if (Exchange.class.equals(cls)) {
            return (Publisher<T>) getPublisher(name);
        }

        return new ConvertingPublisher<T>(getPublisher(name), cls);
    }

    @Override
    public CamelSubscriber getSubscriber(String name) {
        subscribers.computeIfAbsent(name, n -> new CamelSubscriber(name));
        return subscribers.get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> Subscriber<T> getSubscriber(String name, Class<T> type) {
        if (Exchange.class.equals(type)) {
            return (Subscriber<T>) getSubscriber(name);
        }

        return new ConvertingSubscriber<T>(getSubscriber(name), getCamelContext());
    }

    @Override
    public void sendCamelExchange(String name, Exchange exchange, DispatchCallback<Exchange> callback) {
        StreamPayload<Exchange> payload = new StreamPayload<>(exchange, callback);
        getPayloadPublisher(name).publish(payload);
    }

    @Override
    public Publisher<Exchange> request(String name, Object data) {
        Exchange exchange = convertToExchange(data);
        return doRequest(name, exchange);
    }

    @Override
    public Function<?, ? extends Publisher<Exchange>> request(String name) {
        return data -> request(name, data);
    }

    @Override
    public <T> Publisher<T> request(String name, Object data, Class<T> type) {
        return new ConvertingPublisher<>(request(name, data), type);
    }

    protected Publisher<Exchange> doRequest(String name, Exchange data) {
        ReactiveStreamsConsumer consumer = getSubscriber(name).getConsumer();
        if (consumer == null) {
            throw new IllegalStateException("No consumers attached to the stream " + name);
        }

        DelayedMonoPublisher<Exchange> publisher = new DelayedMonoPublisher<>(this.workerPool);

        data.addOnCompletion(new Synchronization() {
            @Override
            public void onComplete(Exchange exchange) {
                publisher.setData(exchange);
            }

            @Override
            public void onFailure(Exchange exchange) {
                Throwable throwable = exchange.getException();
                if (throwable == null) {
                    throwable = new IllegalStateException("Unknown Exception");
                }
                publisher.setException(throwable);
            }
        });

        consumer.process(data, doneSync -> {
        });

        return publisher;
    }

    @Override
    public <T> Function<Object, Publisher<T>> request(String name, Class<T> type) {
        return data -> request(name, data, type);
    }

    private CamelPublisher getPayloadPublisher(String name) {
        publishers.computeIfAbsent(name, n -> new CamelPublisher(this.workerPool, this.context, n));
        return publishers.get(name);
    }

    @Override
    public Publisher<Exchange> publishURI(String uri) {
        publishedUriToStream.computeIfAbsent(uri, u -> {
            try {
                String uuid = context.getUuidGenerator().generateUuid();
                new RouteBuilder() {
                    @Override
                    public void configure() throws Exception {
                        from(u)
                                .to("reactive-streams:" + uuid);
                    }
                }.addRoutesToCamelContext(context);

                return uuid;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to create source reactive stream from direct URI: " + uri, e);
            }
        });
        return getPublisher(publishedUriToStream.get(uri));
    }

    @Override
    public <T> Publisher<T> publishURI(String uri, Class<T> type) {
        return new ConvertingPublisher<T>(publishURI(uri), type);
    }

    @Override
    public Publisher<Exchange> requestURI(String uri, Object data) {
        requestedUriToStream.computeIfAbsent(uri, u -> {
            try {
                String uuid = context.getUuidGenerator().generateUuid();
                new RouteBuilder() {
                    @Override
                    public void configure() throws Exception {
                        from("reactive-streams:" + uuid)
                                .to(u);
                    }
                }.addRoutesToCamelContext(context);

                return uuid;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to create requested reactive stream from direct URI: " + uri, e);
            }
        });
        return request(requestedUriToStream.get(uri), data);
    }

    @Override
    public Function<Object, Publisher<Exchange>> requestURI(String uri) {
        return data -> requestURI(uri, data);
    }

    @Override
    public <T> Publisher<T> requestURI(String uri, Object data, Class<T> type) {
        return new ConvertingPublisher<T>(requestURI(uri, data), type);
    }

    @Override
    public <T> Function<Object, Publisher<T>> requestURI(String uri, Class<T> type) {
        return data -> requestURI(uri, data, type);
    }


    @Override
    public void processFromURI(String uri, Function<? super Publisher<Exchange>, ?> processor) {
        try {
            new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from(uri)
                            .process(exchange -> {
                                Exchange copy = exchange.copy();
                                Object result = processor.apply(new MonoPublisher<>(copy));
                                exchange.getIn().setBody(result);
                            })
                            .process(new UnwrapStreamProcessor());
                }
            }.addRoutesToCamelContext(context);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to add reactive stream processor to the direct URI: " + uri, e);
        }
    }

    @Override
    public <T> void processFromURI(String uri, Class<T> type, Function<? super Publisher<T>, ?> processor) {
        processFromURI(uri, exPub -> processor.apply(new ConvertingPublisher<T>(exPub, type)));
    }

    @Override
    public void attachCamelConsumer(String name, ReactiveStreamsConsumer consumer) {
        getSubscriber(name).attachConsumer(consumer);
    }

    @Override
    public void detachCamelConsumer(String name) {
        getSubscriber(name).detachConsumer();
    }

    @Override
    public void attachCamelProducer(String name, ReactiveStreamsProducer producer) {
        getPayloadPublisher(name).attachProducer(producer);
    }

    @Override
    public void detachCamelProducer(String name) {
        getPayloadPublisher(name).detachProducer();
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.context = camelContext;
    }

    @Override
    public CamelContext getCamelContext() {
        return this.context;
    }

    private Exchange convertToExchange(Object data) {
        Exchange exchange;
        if (data instanceof Exchange) {
            exchange = (Exchange) data;
        } else {
            exchange = new DefaultExchange(context);
            exchange.setPattern(ExchangePattern.InOut);
            exchange.getIn().setBody(data);
        }

        return exchange;
    }

}
