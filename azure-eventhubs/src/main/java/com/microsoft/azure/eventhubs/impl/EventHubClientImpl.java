/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package com.microsoft.azure.eventhubs.impl;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import com.microsoft.azure.eventhubs.AmqpException;
import com.microsoft.azure.eventhubs.BatchOptions;
import com.microsoft.azure.eventhubs.ConnectionStringBuilder;
import com.microsoft.azure.eventhubs.EventData;
import com.microsoft.azure.eventhubs.EventDataBatch;
import com.microsoft.azure.eventhubs.EventHubClient;
import com.microsoft.azure.eventhubs.EventHubException;
import com.microsoft.azure.eventhubs.EventPosition;
import com.microsoft.azure.eventhubs.EventHubRuntimeInformation;
import com.microsoft.azure.eventhubs.IllegalEntityException;
import com.microsoft.azure.eventhubs.PartitionReceiver;
import com.microsoft.azure.eventhubs.PartitionRuntimeInformation;
import com.microsoft.azure.eventhubs.PartitionSender;
import com.microsoft.azure.eventhubs.ReceiverOptions;
import com.microsoft.azure.eventhubs.RetryPolicy;

public final class EventHubClientImpl extends ClientEntity implements EventHubClient {

    /**
     * It will be truncated to 128 characters
     */
    public static String USER_AGENT = null;

    private volatile boolean isSenderCreateStarted;

    private final String eventHubName;
    private final Object senderCreateSync;

    private MessagingFactory underlyingFactory;
    private MessageSender sender;
    private CompletableFuture<Void> createSender;
    private Timer timer;

    private EventHubClientImpl(final ConnectionStringBuilder connectionString, final Executor executor) throws IOException, IllegalEntityException {
        super(StringUtil.getRandomString(), null, executor);

        this.eventHubName = connectionString.getEventHubName();
        this.senderCreateSync = new Object();
    }

    public String getEventHubName() {
        return eventHubName;
    }

    public static CompletableFuture<EventHubClient> create(
            final String connectionString, final RetryPolicy retryPolicy, final Executor executor)
            throws EventHubException, IOException {
        final ConnectionStringBuilder connStr = new ConnectionStringBuilder(connectionString);
        final EventHubClientImpl eventHubClient = new EventHubClientImpl(connStr, executor);

        return MessagingFactory.createFromConnectionString(connectionString.toString(), retryPolicy, executor)
                .thenApplyAsync(new Function<MessagingFactory, EventHubClient>() {
                    @Override
                    public EventHubClient apply(MessagingFactory factory) {
                        eventHubClient.underlyingFactory = factory;
                        eventHubClient.timer = new Timer(factory);
                        return eventHubClient;
                    }
                }, executor);
    }

    public final EventDataBatch createBatch(BatchOptions options) throws EventHubException {

        return ExceptionUtil.sync(() -> {
            int maxSize = this.createInternalSender().thenApplyAsync(
                    (aVoid) -> this.sender.getMaxMessageSize(),
                    this.executor).get();
            if (options.maxMessageSize == null) {
                return new EventDataBatchImpl(maxSize, options.partitionKey);
            }

            if (options.maxMessageSize > maxSize) {
                throw new IllegalArgumentException("The maxMessageSize set in BatchOptions is too large. You set a maxMessageSize of " +
                    options.maxMessageSize + ". The maximum allowed size is " + maxSize + ".");
            }

            return new EventDataBatchImpl(options.maxMessageSize, options.partitionKey);
        }
        );
    }

    @Override
    public final CompletableFuture<Void> send(final EventData data) {
        if (data == null) {
            throw new IllegalArgumentException("EventData cannot be empty.");
        }

        return this.createInternalSender().thenComposeAsync(new Function<Void, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(Void voidArg) {
                return EventHubClientImpl.this.sender.send(((EventDataImpl) data).toAmqpMessage());
            }
        }, this.executor);
    }

    @Override
    public final CompletableFuture<Void> send(final Iterable<EventData> eventDatas) {
        if (eventDatas == null || IteratorUtil.sizeEquals(eventDatas, 0)) {
            throw new IllegalArgumentException("Empty batch of EventData cannot be sent.");
        }

        return this.createInternalSender().thenComposeAsync(new Function<Void, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(Void voidArg) {
                return EventHubClientImpl.this.sender.send(EventDataUtil.toAmqpMessages(eventDatas));
            }
        }, this.executor);
    }

    @Override
    public final CompletableFuture<Void> send(final EventDataBatch eventDatas) {
        if (eventDatas == null || Integer.compare(eventDatas.getSize(), 0) == 0) {
            throw new IllegalArgumentException("Empty batch of EventData cannot be sent.");
        }

        final EventDataBatchImpl eventDataBatch = (EventDataBatchImpl) eventDatas;
        return eventDataBatch.getPartitionKey() != null ?
                this.send(eventDataBatch.getInternalIterable(), eventDataBatch.getPartitionKey()) :
                this.send(eventDataBatch.getInternalIterable());
    }

    @Override
    public final CompletableFuture<Void> send(final EventData eventData, final String partitionKey) {
        if (eventData == null) {
            throw new IllegalArgumentException("EventData cannot be null.");
        }

        if (partitionKey == null) {
            throw new IllegalArgumentException("partitionKey cannot be null");
        }

        return this.createInternalSender().thenComposeAsync(new Function<Void, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(Void voidArg) {
                return EventHubClientImpl.this.sender.send(((EventDataImpl) eventData).toAmqpMessage(partitionKey));
            }
        }, this.executor);
    }

    @Override
    public final CompletableFuture<Void> send(final Iterable<EventData> eventDatas, final String partitionKey) {
        if (eventDatas == null || IteratorUtil.sizeEquals(eventDatas, 0)) {
            throw new IllegalArgumentException("Empty batch of EventData cannot be sent.");
        }

        if (partitionKey == null) {
            throw new IllegalArgumentException("partitionKey cannot be null");
        }

        if (partitionKey.length() > ClientConstants.MAX_PARTITION_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    String.format(Locale.US, "PartitionKey exceeds the maximum allowed length of partitionKey: %s", ClientConstants.MAX_PARTITION_KEY_LENGTH));
        }

        return this.createInternalSender().thenComposeAsync(new Function<Void, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(Void voidArg) {
                return EventHubClientImpl.this.sender.send(EventDataUtil.toAmqpMessages(eventDatas, partitionKey));
            }
        }, this.executor);
    }

    @Override
    public final CompletableFuture<PartitionSender> createPartitionSender(final String partitionId)
            throws EventHubException {
        return PartitionSenderImpl.Create(this.underlyingFactory, this.eventHubName, partitionId, this.executor);
    }

    @Override
    public final CompletableFuture<PartitionReceiver> createReceiver(final String consumerGroupName, final String partitionId, final EventPosition eventPosition)
            throws EventHubException {
        return this.createReceiver(consumerGroupName, partitionId, eventPosition, null);
    }

    @Override
    public final CompletableFuture<PartitionReceiver> createReceiver(final String consumerGroupName, final String partitionId, final EventPosition eventPosition, final ReceiverOptions receiverOptions)
            throws EventHubException {
        return PartitionReceiverImpl.create(this.underlyingFactory, this.eventHubName, consumerGroupName, partitionId, eventPosition, PartitionReceiverImpl.NULL_EPOCH, false, receiverOptions, this.executor);
    }

    @Override
    public final CompletableFuture<PartitionReceiver> createEpochReceiver(final String consumerGroupName, final String partitionId, final EventPosition eventPosition, final long epoch)
            throws EventHubException {
        return this.createEpochReceiver(consumerGroupName, partitionId, eventPosition, epoch, null);
    }

    @Override
    public final CompletableFuture<PartitionReceiver> createEpochReceiver(final String consumerGroupName, final String partitionId, final EventPosition eventPosition, final long epoch, final ReceiverOptions receiverOptions)
            throws EventHubException {
        return PartitionReceiverImpl.create(this.underlyingFactory, this.eventHubName, consumerGroupName, partitionId, eventPosition, epoch, true, receiverOptions, this.executor);
    }

    @Override
    public CompletableFuture<Void> onClose() {
        if (this.underlyingFactory != null) {
            synchronized (this.senderCreateSync) {
                final CompletableFuture<Void> internalSenderClose = this.sender != null
                        ? this.sender.close().thenComposeAsync(new Function<Void, CompletableFuture<Void>>() {
                                @Override
                                public CompletableFuture<Void> apply(Void voidArg) {
                                    return EventHubClientImpl.this.underlyingFactory.close();
                                }
                            }, this.executor)
                        : this.underlyingFactory.close();

                return internalSenderClose;
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> createInternalSender() {
        if (!this.isSenderCreateStarted) {
            synchronized (this.senderCreateSync) {
                if (!this.isSenderCreateStarted) {
                    this.createSender = MessageSender.create(this.underlyingFactory, StringUtil.getRandomString(), this.eventHubName)
                            .thenAcceptAsync(new Consumer<MessageSender>() {
                                public void accept(MessageSender a) {
                                    EventHubClientImpl.this.sender = a;
                                }
                            }, this.executor);

                    this.isSenderCreateStarted = true;
                }
            }
        }

        return this.createSender;
    }

    @Override
    public CompletableFuture<EventHubRuntimeInformation> getRuntimeInformation() {
    	CompletableFuture<EventHubRuntimeInformation> future1 = null;
    	
    	throwIfClosed();

    	Map<String, Object> request = new HashMap<String, Object>();
        request.put(ClientConstants.MANAGEMENT_ENTITY_TYPE_KEY, ClientConstants.MANAGEMENT_EVENTHUB_ENTITY_TYPE);
        request.put(ClientConstants.MANAGEMENT_ENTITY_NAME_KEY, this.eventHubName);
        request.put(ClientConstants.MANAGEMENT_OPERATION_KEY, ClientConstants.READ_OPERATION_VALUE);
        future1 = this.<EventHubRuntimeInformation>addManagementToken(request);

        if (future1 == null) {
	        future1 = managementWithRetry(request).thenComposeAsync(new Function<Map<String, Object>, CompletableFuture<EventHubRuntimeInformation>>() {
				@Override
				public CompletableFuture<EventHubRuntimeInformation> apply(Map<String, Object> rawdata) {
			        CompletableFuture<EventHubRuntimeInformation> future2 = new CompletableFuture<EventHubRuntimeInformation>();
					future2.complete(new EventHubRuntimeInformation(
							(String)rawdata.get(ClientConstants.MANAGEMENT_ENTITY_NAME_KEY),
							((Date)rawdata.get(ClientConstants.MANAGEMENT_RESULT_CREATED_AT)).toInstant(),
							(int)rawdata.get(ClientConstants.MANAGEMENT_RESULT_PARTITION_COUNT),
							(String[])rawdata.get(ClientConstants.MANAGEMENT_RESULT_PARTITION_IDS)));
			        return future2;
				}
	        }, this.executor);
        }
        
        return future1;
    }

    @Override
    public CompletableFuture<PartitionRuntimeInformation> getPartitionRuntimeInformation(String partitionId) {
    	CompletableFuture<PartitionRuntimeInformation> future1 = null;
    	
    	throwIfClosed();

    	Map<String, Object> request = new HashMap<String, Object>();
        request.put(ClientConstants.MANAGEMENT_ENTITY_TYPE_KEY, ClientConstants.MANAGEMENT_PARTITION_ENTITY_TYPE);
        request.put(ClientConstants.MANAGEMENT_ENTITY_NAME_KEY, this.eventHubName);
        request.put(ClientConstants.MANAGEMENT_PARTITION_NAME_KEY, partitionId);
        request.put(ClientConstants.MANAGEMENT_OPERATION_KEY, ClientConstants.READ_OPERATION_VALUE);
        future1 = this.<PartitionRuntimeInformation>addManagementToken(request);

        if (future1 == null) {
	        future1 = managementWithRetry(request).thenComposeAsync(new Function<Map<String, Object>, CompletableFuture<PartitionRuntimeInformation>>() {
				@Override
				public CompletableFuture<PartitionRuntimeInformation> apply(Map<String, Object> rawdata) {
					CompletableFuture<PartitionRuntimeInformation> future2 = new CompletableFuture<PartitionRuntimeInformation>();
					future2.complete(new PartitionRuntimeInformation(
							(String)rawdata.get(ClientConstants.MANAGEMENT_ENTITY_NAME_KEY),
							(String)rawdata.get(ClientConstants.MANAGEMENT_PARTITION_NAME_KEY),
							(long)rawdata.get(ClientConstants.MANAGEMENT_RESULT_BEGIN_SEQUENCE_NUMBER),
							(long)rawdata.get(ClientConstants.MANAGEMENT_RESULT_LAST_ENQUEUED_SEQUENCE_NUMBER),
							(String)rawdata.get(ClientConstants.MANAGEMENT_RESULT_LAST_ENQUEUED_OFFSET),
							((Date)rawdata.get(ClientConstants.MANAGEMENT_RESULT_LAST_ENQUEUED_TIME_UTC)).toInstant()));
					return future2;
				}
	        }, this.executor);
        }
        
        return future1;
    }
    
    private <T> CompletableFuture<T> addManagementToken(Map<String, Object> request)
    {
    	CompletableFuture<T> retval = null;
        try {
        	String audience = String.format("amqp://%s/%s", this.underlyingFactory.getHostName(), this.eventHubName);
        	String token = this.underlyingFactory.getTokenProvider().getToken(audience, ClientConstants.TOKEN_REFRESH_INTERVAL);
			request.put(ClientConstants.MANAGEMENT_SECURITY_TOKEN_KEY, token);
		} 
        catch (InvalidKeyException | NoSuchAlgorithmException | IOException e) {
        	retval = new CompletableFuture<T>();
        	retval.completeExceptionally(e);
		}
    	return retval;
    }
    
    private CompletableFuture<Map<String, Object>> managementWithRetry(Map<String, Object> request) {
        Instant endTime = Instant.now().plus(this.underlyingFactory.getOperationTimeout());
        CompletableFuture<Map<String, Object>> rawdataFuture = new CompletableFuture<Map<String, Object>>();
        
        ManagementRetry retrier = new ManagementRetry(rawdataFuture, endTime, this.underlyingFactory, request);

        final CompletableFuture<?> scheduledTask = this.timer.schedule(retrier, Duration.ZERO);
        if (scheduledTask.isCompletedExceptionally()) {
            rawdataFuture.completeExceptionally(ExceptionUtil.getExceptionFromCompletedFuture(scheduledTask));
        }

        return rawdataFuture;
    }

    private class ManagementRetry implements Runnable {
    	private final CompletableFuture<Map<String, Object>> finalFuture;
    	private final Instant endTime;
    	private final MessagingFactory mf;
    	private final Map<String, Object> request;
    	
    	public ManagementRetry(CompletableFuture<Map<String, Object>> future, Instant endTime, MessagingFactory mf,
    			Map<String, Object> request) {
    		this.finalFuture = future;
    		this.endTime = endTime;
    		this.mf = mf;
    		this.request = request;
    	}
    	
		@Override
		public void run() {
			CompletableFuture<Map<String, Object>> intermediateFuture = this.mf.getManagementChannel().request(this.mf.getReactorScheduler(), request);
			intermediateFuture.whenComplete(new BiConsumer<Map<String, Object>, Throwable>() {
				@Override
				public void accept(Map<String, Object> result, Throwable error) {
					if ((result != null) && (error == null)) {
						// Success!
						ManagementRetry.this.finalFuture.complete(result);
					}
					else {
						Duration remainingTime = Duration.between(Instant.now(), ManagementRetry.this.endTime);
						Exception lastException = null;
						Throwable completeWith = error;
						if (error == null) {
							// Timeout, so fake up an exception to keep getNextRetryInternal happy.
							// It has to be a EventHubException that is set to retryable or getNextRetryInterval will halt the retries.
							lastException = new EventHubException(true, "timed out");
							completeWith = null;
						}
						else if (error instanceof Exception) {
							if ((error instanceof ExecutionException) && (error.getCause() != null) && (error.getCause() instanceof Exception)) {
							    if(error.getCause() instanceof AmqpException) {
							        lastException = ExceptionUtil.toException(((AmqpException) error.getCause()).getError());
                                }
                                else {
							        lastException = (Exception)error.getCause();
                                }

								completeWith = error.getCause();
							}
							else {
								lastException = (Exception)error;
							}
						}
						else {
							lastException = new Exception("got a throwable: " + error.toString());
						}
						Duration waitTime = ManagementRetry.this.mf.getRetryPolicy().getNextRetryInterval(ManagementRetry.this.mf.getClientId(), lastException, remainingTime);
						if (waitTime == null) {
							// Do not retry again, give up and report error.
							if (completeWith == null) {
								ManagementRetry.this.finalFuture.complete(null);
							}
							else {
								ManagementRetry.this.finalFuture.completeExceptionally(completeWith);
							}
						}
						else {
							// The only thing needed here is to schedule a new attempt. Even if the RequestResponseChannel has croaked,
							// ManagementChannel uses FaultTolerantObject, so the underlying RequestResponseChannel will be recreated
							// the next time it is needed.
							ManagementRetry retrier = new ManagementRetry(ManagementRetry.this.finalFuture, ManagementRetry.this.endTime,
									ManagementRetry.this.mf, ManagementRetry.this.request);
							EventHubClientImpl.this.timer.schedule(retrier, waitTime);
						}
					}
				}
			});
		}
    }
}
