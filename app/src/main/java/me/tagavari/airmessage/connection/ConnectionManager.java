package me.tagavari.airmessage.connection;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableTransformer;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableTransformer;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleTransformer;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import me.tagavari.airmessage.MainApplication;
import me.tagavari.airmessage.activity.Messaging;
import me.tagavari.airmessage.activity.Preferences;
import me.tagavari.airmessage.common.Blocks;
import me.tagavari.airmessage.connection.comm4.ClientComm4;
import me.tagavari.airmessage.connection.comm5.ClientComm5;
import me.tagavari.airmessage.connection.exception.AMRequestException;
import me.tagavari.airmessage.connection.listener.CommunicationsManagerListener;
import me.tagavari.airmessage.connection.request.FileFetchRequest;
import me.tagavari.airmessage.connection.request.MassRetrievalRequest;
import me.tagavari.airmessage.connection.task.ChatResponseTask;
import me.tagavari.airmessage.connection.task.MessageUpdateTask;
import me.tagavari.airmessage.connection.task.ModifierUpdateTask;
import me.tagavari.airmessage.data.SharedPreferencesManager;
import me.tagavari.airmessage.enums.AttachmentReqErrorCode;
import me.tagavari.airmessage.enums.ChatCreateErrorCode;
import me.tagavari.airmessage.enums.ConnectionErrorCode;
import me.tagavari.airmessage.enums.ConnectionFeature;
import me.tagavari.airmessage.enums.ConnectionState;
import me.tagavari.airmessage.enums.ConversationState;
import me.tagavari.airmessage.enums.MassRetrievalErrorCode;
import me.tagavari.airmessage.enums.MessageSendErrorCode;
import me.tagavari.airmessage.enums.TrackableRequestCategory;
import me.tagavari.airmessage.helper.ConversationColorHelper;
import me.tagavari.airmessage.messaging.AttachmentInfo;
import me.tagavari.airmessage.messaging.ConversationInfo;
import me.tagavari.airmessage.messaging.MessageInfo;
import me.tagavari.airmessage.messaging.StickerInfo;
import me.tagavari.airmessage.messaging.TapbackInfo;
import me.tagavari.airmessage.redux.ReduxEmitterNetwork;
import me.tagavari.airmessage.redux.ReduxEventAttachmentDownload;
import me.tagavari.airmessage.redux.ReduxEventAttachmentUpload;
import me.tagavari.airmessage.redux.ReduxEventConnection;
import me.tagavari.airmessage.redux.ReduxEventMassRetrieval;
import me.tagavari.airmessage.redux.ReduxEventMessaging;
import me.tagavari.airmessage.util.ActivityStatusUpdate;
import me.tagavari.airmessage.util.CompoundErrorDetails;
import me.tagavari.airmessage.util.ConversationTarget;
import me.tagavari.airmessage.util.ModifierMetadata;
import me.tagavari.airmessage.util.RequestSubject;
import me.tagavari.airmessage.util.TrackableRequest;

public class ConnectionManager {
	private static final String TAG = ConnectionManager.class.getSimpleName();
	
	//Constants
	private static final List<CommunicationsManagerFactory> communicationsPriorityList = Arrays.asList(ClientComm5::new, ClientComm4::new);
	
	private static final long pingExpiryTime = 40 * 1000; //40 seconds
	private static final long keepAliveMillis = 20 * 60 * 1000; //30 * 60 * 1000; //20 minutes
	private static final long keepAliveWindowMillis = 5 * 60 * 1000; //5 minutes
	private static final long[] immediateReconnectDelayMillis = {1000, 5 * 1000, 10 * 1000}; //1 second, 5 seconds, 10 seconds
	private static final long passiveReconnectFrequencyMillis = 30 * 1000;//20 * 60 * 1000; //20 minutes
	
	private static final long requestTimeoutSeconds = 24;
	
	private static final String intentActionPing = "me.tagavari.airmessage.connection.ConnectionManager-Ping";
	private static final String intentActionPassiveReconnect = "me.tagavari.airmessage.connection.ConnectionManager-PassiveReconnect";
	
	//Schedulers
	private final Scheduler uploadScheduler = Schedulers.from(Executors.newSingleThreadExecutor(), true);
	
	//Handler
	private final Handler handler = new Handler(Looper.getMainLooper());
	
	//Random
	private final Random random = new Random();
	
	//Receivers
	private final BroadcastReceiver pingBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			testConnection();
		}
	};
	private final BroadcastReceiver passiveReconnectBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			connectSilently();
		}
	};
	private final Runnable pingExpiryRunnable = () -> disconnect(ConnectionErrorCode.connection);
	
	//Intents
	private final PendingIntent pingPendingIntent, reconnectPendingIntent;
	
	//Connection values
	private CommunicationsManager<?> communicationsManager = null;
	private final Runnable immediateReconnectRunnable = this::connectSilently;
	private boolean immediateReconnectState = false;
	private int immediateReconnectIndex = 0;
	
	//Connection state values
	@ConnectionState private int connState = ConnectionState.disconnected;
	private boolean isConnecting = false;
	private boolean isConnectingSilently = false; //Passive connections are background attempts at reconnecting that don't notify the user
	private boolean connectionEstablished = false;
	private short currentRequestID = 0;
	private int currentCommunicationsIndex = 0;
	
	//Request state values
	private final Map<String, ConversationInfo> pendingConversations = new HashMap<>();
	private boolean isMassRetrievalInProgress = false;
	private boolean isPendingSync = false;
	
	//Server information
	@Nullable
	private String serverInstallationID, serverDeviceName, serverSystemVersion, serverSoftwareVersion;
	
	//Composite disposable
	private final CompositeDisposable compositeDisposable = new CompositeDisposable();
	
	//Response values
	private final Map<Short, RequestSubject<?>> idRequestSubjectMap = new HashMap<>(); //For ID-based requests
	
	//State values
	private boolean disableReconnections = false;
	@Nullable private ConnectionOverride<?> connectionOverride = null;
	
	public ConnectionManager(Context context) {
		pingPendingIntent = PendingIntent.getBroadcast(context, 0, new Intent(intentActionPing), PendingIntent.FLAG_UPDATE_CURRENT);
		context.registerReceiver(pingBroadcastReceiver, new IntentFilter(intentActionPing));
		reconnectPendingIntent = PendingIntent.getBroadcast(context, 0, new Intent(intentActionPassiveReconnect), PendingIntent.FLAG_UPDATE_CURRENT);
		context.registerReceiver(passiveReconnectBroadcastReceiver, new IntentFilter(intentActionPassiveReconnect));
	}
	
	/**
	 * Cleans up this connection manager
	 */
	public void close(Context context) {
		//Clearing all subscriptions
		compositeDisposable.clear();
		
		//Shutting down schedulers
		uploadScheduler.shutdown();
		
		//Unregistering the receivers
		context.unregisterReceiver(pingBroadcastReceiver);
		context.unregisterReceiver(passiveReconnectBroadcastReceiver);
		
		//Cancelling connection test timers
		cancelConnectionTest(context);
		
		//Cancelling all reconnection timers
		stopImmediateReconnect();
		stopPassiveReconnect(context);
	}
	
	private Context getContext() {
		return MainApplication.getInstance();
	}
	
	//Listener values
	private final CommunicationsManagerListener communicationsManagerListener = new CommunicationsManagerListener() {
		@Override
		public void onOpen(String installationID, String deviceName, String systemVersion, String softwareVersion) {
			connectionEstablished = true;
			
			//Recording the server information
			serverInstallationID = installationID;
			serverDeviceName = deviceName;
			serverSystemVersion = systemVersion;
			serverSoftwareVersion = softwareVersion;
			
			//Updating shared preferences
			long lastConnectionTime = SharedPreferencesManager.getLastConnectionTime(getContext());
			SharedPreferencesManager.setLastConnectionTime(getContext(), System.currentTimeMillis());
			
			//Updating the state
			updateStateConnected();
			isConnecting = false;
			isConnectingSilently = false;
			
			//Resetting the reconnection values
			stopImmediateReconnect();
			stopPassiveReconnect(getContext());
			
			//Checking if an installation ID was provided
			boolean isNewServer; //Is this server different from the one we connected to last time?
			boolean isNewServerSinceSync; //Is this server different from the one we connected to last time, since we last synced our messages?
			if(installationID != null) {
				//Getting the last installation ID
				String lastInstallationID = SharedPreferencesManager.getLastConnectionInstallationID(getContext());
				String lastInstallationIDSinceSync = SharedPreferencesManager.getLastSyncInstallationID(getContext());
				
				//If the installation ID changed, we are connected to a new server
				isNewServer = !installationID.equals(lastInstallationID);
				
				//Updating the saved value
				if(isNewServer) SharedPreferencesManager.setLastConnectionInstallationID(getContext(), installationID);
				
				//"notrigger" is assigned to this value when upgrading from 0.5.X to prevent sync prompts after upgrading
				if("notrigger".equals(lastInstallationIDSinceSync)) {
					//Don't sync messages
					isNewServerSinceSync = false;
					
					//Update the saved value for next time
					SharedPreferencesManager.setLastSyncInstallationID(getContext(), installationID);
				} else {
					isNewServerSinceSync = !installationID.equals(lastInstallationIDSinceSync);
				}
			} else {
				//No way to tell
				isNewServer = false;
				isNewServerSinceSync = false;
			}
			
			//Retrieving the pending conversation info
			fetchPendingConversations();
			
			//Checking if we are connected to a new server
			if(isNewServer) {
				//Resetting the last message ID
				SharedPreferencesManager.removeLastServerMessageID(getContext());
			} else {
				long lastServerMessageID = SharedPreferencesManager.getLastServerMessageID(getContext());
				
				//Fetching missed messages
				if(communicationsManager.isFeatureSupported(ConnectionFeature.idBasedRetrieval) && lastServerMessageID != -1) {
					//Fetching messages since the last message ID
					requestMessagesIDRange(lastServerMessageID);
				} else {
					//Fetching the messages since the last connection time
					requestMessagesTimeRange(lastConnectionTime, System.currentTimeMillis());
				}
			}
			
			//Checking if we are connected to a new server since syncing (and thus should prompt the user to sync)
			if(isNewServerSinceSync) {
				//Setting the state
				isPendingSync = true;
				
				//Sending an update
				ReduxEmitterNetwork.getMessageUpdateSubject().onNext(new ReduxEventMessaging.Sync(serverInstallationID, serverDeviceName));
			}
		}
		
		@Override
		public void onClose(@ConnectionErrorCode int errorCode) {
			isConnecting = false;
			
			//Checking if the disconnection is caused by a protocol error
			if((errorCode == ConnectionErrorCode.connection || errorCode == ConnectionErrorCode.externalError)) {
				//Checking if we have yet to establish a connection, and there are older protocol versions available to use
				if(!connectionEstablished && currentCommunicationsIndex + 1 < communicationsPriorityList.size()) {
					//Falling back to an older protocol
					//updateStateConnecting();
					connectFromList(getContext(), currentCommunicationsIndex + 1);
					return;
				}
				//Checking if we have already established a connection, and we are allowed to run automatic reconnections
				else if(connectionEstablished && !disableReconnections) {
					//Trying to start an immediate reconnection
					boolean result = continueImmediateReconnect();
					if(result) {
						//updateStateConnecting();
						return;
					}
				}
			}
			
			if(connState != ConnectionState.disconnected) {
				//Resetting the connection established state
				connectionEstablished = false;
				
				//Failing all pending requests
				for(RequestSubject<?> subject : new ArrayList<>(idRequestSubjectMap.values())) subject.onExpire();
				
				//Clearing the pending sync state
				isPendingSync = false;
				
				//Updating the state
				updateStateDisconnected(errorCode);
				
				//Cancelling connection test timers
				cancelConnectionTest(getContext());
				
				if(!disableReconnections) {
					//Starting passive reconnection
					startPassiveReconnect(getContext());
				}
			}
		}
		
		@Override
		public void onPacket() {
			if(connectionEstablished) {
				//Updating the last connection time
				SharedPreferencesManager.setLastConnectionTime(getContext(), System.currentTimeMillis());
				
				//Resetting connection tests
				resetConnectionTest(getContext());
			}
		}
		
		@Override
		public void onMessageUpdate(Collection<Blocks.ConversationItem> data) {
			//Loading the foreground conversations (needs to be done on the main thread)
			Single.fromCallable(Messaging::getForegroundConversations)
					.subscribeOn(AndroidSchedulers.mainThread())
					.flatMap(foregroundConversations -> MessageUpdateTask.create(getContext(), foregroundConversations, data, Preferences.getPreferenceAutoDownloadAttachments(getContext())))
					.observeOn(AndroidSchedulers.mainThread())
					.doOnSuccess(response -> {
						//Adding the conversations as pending conversations and retrieving pending conversation information
						pendingConversations.putAll(response.getIncompleteServerConversations().stream().collect(Collectors.toMap(ConversationInfo::getGUID, conversation -> conversation)));
						
						//Emitting any generated events
						for(ReduxEventMessaging event : response.getEvents()) {
							ReduxEmitterNetwork.getMessageUpdateSubject().onNext(event);
						}
						
						//Fetching pending conversations
						fetchPendingConversations();
						
						//Downloading attachments
						if(response.getCollectedAttachments() != null) {
							for(Pair<MessageInfo, AttachmentInfo> attachmentData : response.getCollectedAttachments()) {
								//Ignoring outgoing attachments
								if(attachmentData.first.isOutgoing()) continue;
								ConnectionTaskManager.downloadAttachment(ConnectionManager.this, attachmentData.first.getLocalID(), attachmentData.second.getLocalID(), attachmentData.second.getGUID(), attachmentData.second.getFileName());
							}
						}
					}).subscribe();
		}
		
		@Override
		public void onMassRetrievalStart(short requestID, Collection<Blocks.ConversationInfo> conversations, int messageCount) {
			//Getting the request
			RequestSubject.Publish<ReduxEventMassRetrieval> subject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Initializing the request
			MassRetrievalRequest massRetrievalRequest = subject.getRequestData();
			compositeDisposable.add(
					massRetrievalRequest.handleInitialInfo(conversations, messageCount)
							.subscribe((addedConversations) -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								ReduxEventMassRetrieval event = new ReduxEventMassRetrieval.Start(addedConversations, messageCount);
								localSubject.get().onNext(event);
								ReduxEmitterNetwork.getMassRetrievalUpdateSubject().onNext(event);
							}, (error) -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								if(error instanceof IllegalArgumentException) {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.localBadResponse, error));
								} else {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.unknown, error));
								}
								idRequestSubjectMap.remove(requestID);
							})
			);
		}
		
		@Override
		public void onMassRetrievalUpdate(short requestID, int responseIndex, Collection<Blocks.ConversationItem> data) {
			//Getting the request
			RequestSubject.Publish<ReduxEventMassRetrieval> subject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Saving the data
			MassRetrievalRequest massRetrievalRequest = subject.getRequestData();
			compositeDisposable.add(
					massRetrievalRequest.handleMessages(getContext(), responseIndex, data)
							.subscribe((addedItems) -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								ReduxEventMassRetrieval event = new ReduxEventMassRetrieval.Progress(addedItems, massRetrievalRequest.getMessagesReceived(), massRetrievalRequest.getTotalMessageCount());
								
								localSubject.get().onNext(event);
								ReduxEmitterNetwork.getMassRetrievalUpdateSubject().onNext(event);
							}, (error) -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								if(error instanceof IllegalArgumentException) {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.localBadResponse));
								} else {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.unknown, error));
								}
								idRequestSubjectMap.remove(requestID);
							})
			);
		}
		
		@Override
		public void onMassRetrievalComplete(short requestID) {
			//Getting the request
			RequestSubject.Publish<ReduxEventMassRetrieval> subject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Completing the request
			MassRetrievalRequest massRetrievalRequest = subject.getRequestData();
			compositeDisposable.add(
					massRetrievalRequest.complete()
							.subscribe(() -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								ReduxEventMassRetrieval event = new ReduxEventMassRetrieval.Complete();
								localSubject.get().onNext(event);
								ReduxEmitterNetwork.getMassRetrievalUpdateSubject().onNext(event);
								localSubject.onComplete();
							}, (error) -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								massRetrievalRequest.cancel();
								localSubject.onError(new AMRequestException(MassRetrievalErrorCode.localBadResponse));
								idRequestSubjectMap.remove(requestID);
							})
			);
		}
		
		@Override
		public void onMassRetrievalFail(short requestID) {
			//Getting the request
			RequestSubject.Publish<ReduxEventMassRetrieval> subject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Cancelling the request
			MassRetrievalRequest massRetrievalRequest = subject.getRequestData();
			try {
				massRetrievalRequest.cancel();
			} catch(IOException exception) {
				exception.printStackTrace();
			}
			
			//Failing the request
			subject.onError(new AMRequestException(MassRetrievalErrorCode.localBadResponse));
			idRequestSubjectMap.remove(requestID);
		}
		
		@Override
		public void onMassRetrievalFileStart(short requestID, String fileGUID, String fileName, @Nullable Function<OutputStream, OutputStream> streamWrapper) {
			//Getting the request
			RequestSubject.Publish<ReduxEventMassRetrieval> subject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Initializing the attachment request
			MassRetrievalRequest massRetrievalRequest = subject.getRequestData();
			compositeDisposable.add(
					massRetrievalRequest.initializeAttachment(getContext(), fileGUID, fileName, streamWrapper)
							.subscribe(() -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								ReduxEventMassRetrieval event = new ReduxEventMassRetrieval.File();
								localSubject.get().onNext(event);
								ReduxEmitterNetwork.getMassRetrievalUpdateSubject().onNext(event);
							}, (error) -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								if(error instanceof IOException) {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.localIO, error));
								} else if(error instanceof IllegalArgumentException) {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.localBadResponse, error));
								} else {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.unknown, error));
								}
								idRequestSubjectMap.remove(requestID);
							})
			);
		}
		
		@Override
		public void onMassRetrievalFileProgress(short requestID, int responseIndex, String fileGUID, byte[] fileData) {
			//Getting the request
			RequestSubject.Publish<ReduxEventMassRetrieval> subject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Writing the data
			MassRetrievalRequest massRetrievalRequest = subject.getRequestData();
			compositeDisposable.add(
					massRetrievalRequest.writeChunkAttachment(fileGUID, responseIndex, fileData)
							.subscribe(() -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								ReduxEventMassRetrieval event = new ReduxEventMassRetrieval.File();
								localSubject.get().onNext(event);
								ReduxEmitterNetwork.getMassRetrievalUpdateSubject().onNext(event);
							}, (error) -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								if(error instanceof IOException) {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.localIO, error));
								} else if(error instanceof IllegalArgumentException) {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.localBadResponse, error));
								} else {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.unknown, error));
								}
								idRequestSubjectMap.remove(requestID);
							})
			);
		}
		
		@Override
		public void onMassRetrievalFileComplete(short requestID, String fileGUID) {
			//Getting the request
			RequestSubject.Publish<ReduxEventMassRetrieval> subject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Writing the data
			MassRetrievalRequest massRetrievalRequest = subject.getRequestData();
			compositeDisposable.add(
					massRetrievalRequest.finishAttachment(getContext(), fileGUID)
							.subscribe(() -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								ReduxEventMassRetrieval event = new ReduxEventMassRetrieval.File();
								localSubject.get().onNext(event);
								ReduxEmitterNetwork.getMassRetrievalUpdateSubject().onNext(event);
							}, (error) -> {
								//Getting the request
								RequestSubject.Publish<ReduxEventMassRetrieval> localSubject = (RequestSubject.Publish<ReduxEventMassRetrieval>) idRequestSubjectMap.get(requestID);
								if(localSubject == null) return;
								
								if(error instanceof IOException) {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.localIO));
								} else if(error instanceof IllegalArgumentException) {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.localBadResponse));
								} else {
									localSubject.onError(new AMRequestException(MassRetrievalErrorCode.unknown));
								}
								idRequestSubjectMap.remove(requestID);
							})
			);
		}
		
		@Override
		public void onConversationUpdate(Collection<Blocks.ConversationInfo> data) {
			List<ConversationInfo> unavailableConversations = new ArrayList<>();
			List<ConversationInfo> availableConversations = new ArrayList<>();
			
			for(Blocks.ConversationInfo structConversationInfo : data) {
				//Finding the conversation in the pending list
				ConversationInfo conversationInfo = pendingConversations.get(structConversationInfo.guid);
				if(conversationInfo == null) continue;
				pendingConversations.remove(structConversationInfo.guid);
				
				//Ignoring if the conversation is not in the state 'incomplete server'
				if(conversationInfo.getState() != ConversationState.incompleteServer) continue;
				
				//Checking if the conversation is available
				if(structConversationInfo.available) {
					//Setting the conversation details
					conversationInfo.setServiceType(structConversationInfo.service);
					conversationInfo.setTitle(structConversationInfo.name);
					conversationInfo.setConversationColor(ConversationColorHelper.getDefaultConversationColor(conversationInfo.getGUID()));
					conversationInfo.setMembers(ConversationColorHelper.getColoredMembers(structConversationInfo.members, conversationInfo.getConversationColor(), conversationInfo.getGUID()));
					conversationInfo.setState(ConversationState.ready);
					
					//Marking the conversation as valid (and to be saved)
					availableConversations.add(conversationInfo);
				}
				//Otherwise marking the conversation as invalid
				else unavailableConversations.add(conversationInfo);
			}
			
			//Creating and running the asynchronous task
			ChatResponseTask.create(getContext(), availableConversations, unavailableConversations)
					.doOnSuccess(result -> {
						ReduxEmitterNetwork.getMessageUpdateSubject().onNext(new ReduxEventMessaging.ConversationUpdate(result.getAvailableConversationItems(), result.getTransferredConversations()));
					}).subscribe();
		}
		
		@Override
		public void onModifierUpdate(Collection<Blocks.ModifierInfo> data) {
			//Writing modifiers to disk
			ModifierUpdateTask.create(getContext(), data).doOnSuccess(result -> {
				//Pushing emitter updates
				for(ActivityStatusUpdate statusUpdate : result.getActivityStatusUpdates()) {
					ReduxEmitterNetwork.getMessageUpdateSubject().onNext(new ReduxEventMessaging.MessageState(statusUpdate.getMessageID(), statusUpdate.getMessageState(), statusUpdate.getDateRead()));
				}
				for(Pair<StickerInfo, ModifierMetadata> sticker : result.getStickerModifiers()) ReduxEmitterNetwork.getMessageUpdateSubject().onNext(new ReduxEventMessaging.StickerAdd(sticker.first, sticker.second));
				for(Pair<TapbackInfo, ModifierMetadata> tapback : result.getTapbackModifiers()) ReduxEmitterNetwork.getMessageUpdateSubject().onNext(new ReduxEventMessaging.TapbackUpdate(tapback.first, tapback.second, true));
				for(Pair<TapbackInfo, ModifierMetadata> tapback : result.getTapbackRemovals()) ReduxEmitterNetwork.getMessageUpdateSubject().onNext(new ReduxEventMessaging.TapbackUpdate(tapback.first, tapback.second, false));
			}).subscribe();
		}
		
		@Override
		public void onFileRequestStart(short requestID, long length, @Nullable Function<OutputStream, OutputStream> streamWrapper) {
			//Getting the request
			RequestSubject.Publish<ReduxEventAttachmentDownload> subject = (RequestSubject.Publish<ReduxEventAttachmentDownload>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Initializing the request
			FileFetchRequest fileFetchRequest = subject.getRequestData();
			try {
				fileFetchRequest.initialize(getContext(), length, streamWrapper);
			} catch(IOException exception) {
				subject.onError(new AMRequestException(AttachmentReqErrorCode.localIO));
				idRequestSubjectMap.remove(requestID);
				return;
			}
			
			//Sending an update
			subject.get().onNext(new ReduxEventAttachmentDownload.Start(length));
		}
		
		@Override
		public void onFileRequestData(short requestID, int responseIndex, byte[] data) {
			//Getting the request
			RequestSubject.Publish<ReduxEventAttachmentDownload> subject = (RequestSubject.Publish<ReduxEventAttachmentDownload>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Writing the data
			FileFetchRequest fileFetchRequest = subject.getRequestData();
			compositeDisposable.add(
					fileFetchRequest.writeChunk(responseIndex, data).subscribe((writtenLength) -> {
						subject.get().onNext(new ReduxEventAttachmentDownload.Progress(writtenLength, fileFetchRequest.getTotalLength()));
					}, (error) -> {
						if(error instanceof IOException) {
							subject.onError(new AMRequestException(AttachmentReqErrorCode.localIO));
						} else if(error instanceof IllegalArgumentException) {
							subject.onError(new AMRequestException(AttachmentReqErrorCode.localBadResponse));
						} else {
							subject.onError(new AMRequestException(AttachmentReqErrorCode.unknown));
						}
						idRequestSubjectMap.remove(requestID);
					})
			);
		}
		
		@Override
		public void onFileRequestComplete(short requestID) {
			//Getting the request
			RequestSubject.Publish<ReduxEventAttachmentDownload> subject = (RequestSubject.Publish<ReduxEventAttachmentDownload>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Completing the request
			FileFetchRequest fileFetchRequest = subject.getRequestData();
			compositeDisposable.add(
					fileFetchRequest.complete(getContext()).subscribe((attachmentFile) -> {
						subject.get().onNext(new ReduxEventAttachmentDownload.Complete(attachmentFile));
						subject.onComplete();
						ReduxEmitterNetwork.getMessageUpdateSubject().onNext(new ReduxEventMessaging.AttachmentFile(fileFetchRequest.getMessageID(), fileFetchRequest.getAttachmentID(), attachmentFile));
					}, (error) -> {
						subject.onError(new AMRequestException(AttachmentReqErrorCode.localIO));
						idRequestSubjectMap.remove(requestID);
					})
			);
		}
		
		@Override
		public void onFileRequestFail(short requestID, int errorCode) {
			//Getting the request
			RequestSubject.Publish<ReduxEventAttachmentDownload> subject = (RequestSubject.Publish<ReduxEventAttachmentDownload>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			//Failing the request
			subject.onError(new AMRequestException(AttachmentReqErrorCode.localIO));
			idRequestSubjectMap.remove(requestID);
		}
		
		@Override
		public void onIDUpdate(long messageID) {
			SharedPreferencesManager.setLastServerMessageID(getContext(), messageID);
		}
		
		@Override
		public void onSendMessageSuccess(short requestID) {
			//Resolving the completable
			RequestSubject.EmptyCompletable<?> subject = (RequestSubject.EmptyCompletable<?>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			subject.onComplete();
			
			idRequestSubjectMap.remove(requestID);
		}
		
		@Override
		public void onSendMessageFail(short requestID, CompoundErrorDetails.MessageSend error) {
			//Failing the completable
			RequestSubject<?> subject = idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			subject.onError(error.toException());
			
			idRequestSubjectMap.remove(requestID);
		}
		
		@Override
		public void onCreateChatSuccess(short requestID, String chatGUID) {
			//Resolving the completable
			RequestSubject.Single<String> subject = (RequestSubject.Single<String>) idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			subject.get().onSuccess(chatGUID);
			
			idRequestSubjectMap.remove(requestID);
		}
		
		@Override
		public void onCreateChatError(short requestID, CompoundErrorDetails.ChatCreate error) {
			//Failing the completable
			RequestSubject<?> subject = idRequestSubjectMap.get(requestID);
			if(subject == null) return;
			
			subject.onError(error.toException());
			
			idRequestSubjectMap.remove(requestID);
		}
	};
	
	/**
	 * Finds an existing request
	 * @param category The category of the trackable request
	 * @param key The key of the trackable request
	 * @param <S> The result value of the request subject
	 * @param <R> The type of the request subject
	 * @param <K> The type of the key
	 * @return A matching request subject of type R, or NULL if not found
	 */
	@Nullable
	public <S, R extends RequestSubject<S>, K> R findRequest(@TrackableRequestCategory int category, K key) {
		return (R) idRequestSubjectMap.values().stream().filter(requestSubject -> {
			//Ignoring if this subject isn't trackable
			if(!(requestSubject.getRequestData() instanceof TrackableRequest)) return false;
			
			//Ignoring if this trackable request is for a different category
			TrackableRequest<?> trackableRequest = requestSubject.getRequestData();
			if(trackableRequest.getCategory() != category) return false;
			
			//Ignoring if the keys don't match
			K trackableKey = (K) trackableRequest.getValue();
			if(!Objects.equals(key, trackableKey)) return false;
			
			return true;
		}).findAny().orElse(null);
	}
	
	/**
	 * Sets the state to connecting
	 */
	private void updateStateConnecting() {
		connState = ConnectionState.connecting;
		ReduxEmitterNetwork.getConnectionStateSubject().onNext(new ReduxEventConnection.Connecting());
	}
	
	/**
	 * Sets the state to connected
	 */
	private void updateStateConnected() {
		connState = ConnectionState.connected;
		ReduxEmitterNetwork.getConnectionStateSubject().onNext(new ReduxEventConnection.Connected());
	}
	
	/**
	 * Sets the state to disconnected
	 * @param code The error code to notify listeners of
	 */
	private void updateStateDisconnected(@ConnectionErrorCode int code) {
		connState = ConnectionState.disconnected;
		ReduxEmitterNetwork.getConnectionStateSubject().onNext(new ReduxEventConnection.Disconnected(code));
	}
	
	/**
	 * Gets the next request ID
	 */
	private short generateRequestID() {
		return ++currentRequestID;
	}
	
	/**
	 * Connects to the server using the latest communications version
	 */
	public void connect() {
		//Ignoring if we're already connecting
		if(connState != ConnectionState.disconnected || isConnecting) return;
		
		//Checking if a passive reconnection is in progress
		if(isConnectingSilently) {
			//Bringing the state from passive to the foreground
			updateStateConnecting();
			isConnectingSilently = false;
			
			return;
		}
		
		//Cancelling the immediate reconnect timer if it's running
		if(immediateReconnectState) {
			stopImmediateReconnect();
		}
		
		//Setting the state to connecting
		updateStateConnecting();
		isConnecting = true;
		
		//Connecting from the top of the priority list
		connectFromList(getContext(), 0);
	}
	
	/**
	 * Connects to the server without notifying or updating the state, using the current communications index
	 *
	 * If {@link #connect()} is called while a passive reconnection is taking place,
	 * the state will be updated to match
	 */
	public void connectSilently() {
		//Ignoring if we're already connecting (though it's OK if the display state doesn't match)
		if(isConnecting) return;
		
		//Recording the state
		isConnectingSilently = true;
		isConnecting = true;
		
		//Connecting from the top of the priority list
		connectFromList(getContext(), 0);
	}
	
	/**
	 * Connects from the specified index down the priority list
	 */
	private void connectFromList(Context context, int index) {
		//Recording the index
		currentCommunicationsIndex = index;
		
		//Getting the parameters
		int proxyType = connectionOverride == null ? SharedPreferencesManager.getProxyType(context) : connectionOverride.getProxyType();
		Object overrideValue = connectionOverride == null ? null : connectionOverride.getValue();
		
		//Creating and starting the communications manager
		communicationsManager = communicationsPriorityList.get(index).create(communicationsManagerListener, proxyType);
		communicationsManager.connect(context, overrideValue);
	}
	
	/**
	 * Disconnects from the server
	 */
	public void disconnect(@ConnectionErrorCode int code) {
		//Ignoring if we're not connected
		if(connState != ConnectionState.connected) return;
		
		if(communicationsManager != null) communicationsManager.disconnect(code);
	}
	
	/**
	 * Starts or advances an immediate reconnection
	 * @return TRUE if the passive reconnection was scheduled, or FALSE if none could be scheduled
	 */
	private boolean continueImmediateReconnect() {
		//Checking if we aren't already doing immediate reconnect
		if(!immediateReconnectState) {
			//Initialize state
			immediateReconnectState = true;
			immediateReconnectIndex = 0;
		} else {
			//Failing if we are at the end of our attempts
			if(immediateReconnectIndex + 1 >= immediateReconnectDelayMillis.length) {
				return false;
			}
			
			//Incrementing the index
			immediateReconnectIndex++;
		}
		
		//Scheduling the passive reconnection
		handler.postDelayed(immediateReconnectRunnable, immediateReconnectDelayMillis[immediateReconnectIndex] + random.nextInt(1000));
		
		return true;
	}
	
	/**
	 * Stops immediate reconnections
	 */
	private void stopImmediateReconnect() {
		handler.removeCallbacks(immediateReconnectRunnable);
		immediateReconnectState = false;
	}
	
	/**
	 * Starts the passive background reconnection clock
	 */
	private void startPassiveReconnect(Context context) {
		context.getSystemService(AlarmManager.class).setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + passiveReconnectFrequencyMillis / 2,
				passiveReconnectFrequencyMillis,
				reconnectPendingIntent);
	}
	
	/**
	 * Stops the passive background reconnection clock
	 */
	private void stopPassiveReconnect(Context context) {
		//Cancelling passive reconnection
		context.getSystemService(AlarmManager.class).cancel(reconnectPendingIntent);
	}
	
	/**
	 * Gets the current connection state
	 */
	@ConnectionState
	public int getState() {
		return connState;
	}
	
	/**
	 * Gets if this connection manager is connected to the server (can send and receive messages)
	 */
	public boolean isConnected() {
		return connState == ConnectionState.connected;
	}
	
	/**
	 * Sends a FCM push token to AirMessage Cloud to receive push notifications
	 * @param token The token to send
	 * @return Whether the token was successfully sent
	 */
	public boolean sendPushToken(String token) {
		//Failing immediately if there is no network connection
		if(!isConnected()) return false;
		
		//Sending the token
		return communicationsManager.sendPushToken(token);
	}
	
	/**
	 * Sends a ping to the server and waits for a response
	 * The connection is closed if no response is received in time.
	 * This function is to be used when there is no network traffic present to validate the connection.
	 */
	public void testConnection() {
		//Sending the ping
		communicationsManager.sendPing();
		
		//Starting the ping timeout
		handler.postDelayed(pingExpiryRunnable, pingExpiryTime);
	}
	
	/**
	 * Resets all connection test timers, and schedules new timers for later
	 */
	public void resetConnectionTest(Context context) {
		//Cancelling the ping timeout
		handler.removeCallbacks(pingExpiryRunnable);
		
		//Resetting the ping timer
		context.getSystemService(AlarmManager.class).setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + keepAliveMillis - keepAliveWindowMillis,
				keepAliveWindowMillis * 2,
				pingPendingIntent);
	}
	
	/**
	 * Cancels all connection test timers
	 */
	public void cancelConnectionTest(Context context) {
		//Cancelling the ping timeout
		handler.removeCallbacks(pingExpiryRunnable);
		
		//Cancelling the ping timer
		context.getSystemService(AlarmManager.class).cancel(pingPendingIntent);
	}
	
	/**
	 * Sends a text message to a conversation
	 * @param conversation The conversation to send to
	 * @param message The message to send
	 * @return An completable to track the state of the request, or an {@link AMRequestException} with a {@link MessageSendErrorCode}
	 */
	public Completable sendMessage(ConversationTarget conversation, String message) {
		final Throwable error = new AMRequestException(MessageSendErrorCode.localNetwork);
		
		//Failing immediately if there is no network connection
		if(!isConnected()) return Completable.error(error);
		
		//Getting the request ID
		short requestID = generateRequestID();
		
		//Sending the message
		boolean result = communicationsManager.sendMessage(requestID, conversation, message);
		if(!result) return Completable.error(error);
		
		//Adding the request
		return queueCompletableIDRequest(requestID, error);
	}
	
	/**
	 * Sends an attachment file to a conversation
	 * @param conversation The conversation to send to
	 * @param file The file to send
	 * @return An observable to track the progress of the upload, or an {@link AMRequestException} with a {@link MessageSendErrorCode}
	 */
	public Observable<ReduxEventAttachmentUpload> sendFile(ConversationTarget conversation, File file) {
		final Throwable error = new AMRequestException(MessageSendErrorCode.localNetwork);
		
		//Failing immediately if there is no network connection
		if(!isConnected()) return Observable.error(error);
		
		//Getting the request ID
		short requestID = generateRequestID();
		
		//Creating the subject
		PublishSubject<ReduxEventAttachmentUpload> subject = PublishSubject.create();
		
		//Adding the request to the list
		idRequestSubjectMap.put(requestID, new RequestSubject.Publish<>(subject, error));
		
		//Sending the file (not passing completions to the subject, since we'll want to handle those when we receive a response instead)
		Observable.concat(communicationsManager.sendFile(requestID, conversation, file), Observable.never())
				.subscribeOn(uploadScheduler)
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(subject);
		
		//Adding a timeout
		return subject.compose(composeTimeoutIDObservable(requestID, error));
	}
	
	/**
	 * Fetches the data of an attachment
	 * @param messageLocalID the local ID of the attachment's message
	 * @param attachmentLocalID The local ID of the attachment
	 * @param attachmentGUID The GUID of the attachment
	 * @param attachmentName The name of the attachment file
	 * @return An observable to track the progress of the download, or an {@link AMRequestException} with a {@link AttachmentReqErrorCode}
	 */
	public Observable<ReduxEventAttachmentDownload> fetchAttachment(long messageLocalID, long attachmentLocalID, String attachmentGUID, String attachmentName) {
		final Throwable error = new AMRequestException(AttachmentReqErrorCode.localTimeout);
		
		//Failing immediately if there is no network connection
		if(!isConnected()) return Observable.error(error);
		
		//Getting the request ID
		short requestID = generateRequestID();
		
		//Sending the request
		boolean result = communicationsManager.requestAttachmentDownload(requestID, attachmentGUID);
		if(!result) return Observable.error(error);
		
		//Adding the request
		FileFetchRequest fileFetchRequest = new FileFetchRequest(messageLocalID, attachmentLocalID, attachmentName);
		return this.<ReduxEventAttachmentDownload>queueObservableIDRequest(requestID, error, fileFetchRequest).doOnError((observableError) -> {
			//Cleaning up
			fileFetchRequest.cancel();
		});
	}
	
	/**
	 * Creates a chat
	 * @param members The addresses of the members of the chat
	 * @param service The service of the chat
	 * @return A single representing the GUID of the chat, or an {@link AMRequestException} with a {@link ChatCreateErrorCode}
	 */
	public Single<String> createChat(String[] members, String service) {
		final Throwable error = new AMRequestException(ChatCreateErrorCode.network);
		
		//Failing immediately if there is no network connection
		if(!isConnected()) return Single.error(error);
		
		//Getting the request ID
		short requestID = generateRequestID();
		
		//Sending the request
		boolean result = communicationsManager.requestChatCreation(requestID, members, service);
		if(!result) return Single.error(error);
		
		//Adding the request
		return queueSingleIDRequest(requestID, error);
	}
	
	/**
	 * Requests data for pending conversations from the server
	 */
	public void fetchPendingConversations() {
		//Failing immediately if there is no network connection
		if(!isConnected()) return;
		
		//Sending the request
		communicationsManager.requestConversationInfo(pendingConversations.keySet());
	}
	
	/**
	 * Requests messages between the time bounds from the server
	 * @param timeLower The lower time requirement in milliseconds
	 * @param timeUpper The upper time requirement in milliseconds
	 */
	public void requestMessagesTimeRange(long timeLower, long timeUpper) {
		//Failing immediately if there is no network connection
		if(!isConnected()) return;
		
		//Sending the request
		communicationsManager.requestRetrievalTime(timeLower, timeUpper);
	}
	
	/**
	 * Requests messages since above the specified ID from the server
	 * @param idLower The ID of the message to receive messages since
	 */
	public void requestMessagesIDRange(long idLower) {
		//Failing immediately if there is no network connection
		if(!isConnected()) return;
		
		//Sending the request
		communicationsManager.requestRetrievalID(idLower);
	}
	
	/**
	 * Requests a mass message download from the server
	 * @param params The parameters to define what to download
	 */
	public Observable<ReduxEventMassRetrieval> fetchMassConversationData(MassRetrievalParams params) {
		final Throwable error = new Throwable("Mass retrieval error");
		
		//Failing immediately if there is already a mass retrieval in progress or there is no network connection
		if(isMassRetrievalInProgress || !isConnected()) return Observable.error(error);
		
		//Getting the request ID
		short requestID = generateRequestID();
		
		//Sending the request
		boolean result = communicationsManager.requestRetrievalAll(requestID, params);
		if(!result) return Observable.error(error);
		
		//Updating the mass retrieval state
		isMassRetrievalInProgress = true;
		
		//Adding the request
		MassRetrievalRequest massRetrievalRequest = new MassRetrievalRequest();
		return this.<ReduxEventMassRetrieval>queueObservableIDRequest(requestID, error, massRetrievalRequest).doOnError((observableError) -> {
			//Getting the error code
			int errorCode;
			if(observableError instanceof AMRequestException) {
				errorCode = ((AMRequestException) observableError).getErrorCode();
			} else {
				errorCode = MassRetrievalErrorCode.unknown;
				FirebaseCrashlytics.getInstance().recordException(observableError);
			}
			
			//Cleaning up
			massRetrievalRequest.cancel();
			
			//Emitting an update
			ReduxEmitterNetwork.getMassRetrievalUpdateSubject().onNext(new ReduxEventMassRetrieval.Error(errorCode));
			Log.w(TAG, "Mass retrieval failed", observableError);
		}).doOnTerminate(() -> {
			//Updating the mass retrieval state
			isMassRetrievalInProgress = false;
		});
	}
	
	private CompletableTransformer composeTimeoutIDCompletable(short requestID, Throwable throwable) {
		return completable -> completable.timeout(requestTimeoutSeconds, TimeUnit.SECONDS, Completable.error(throwable))
				.observeOn(AndroidSchedulers.mainThread())
				.doOnTerminate(() -> idRequestSubjectMap.remove(requestID));
	}
	
	private <T> SingleTransformer<T, T> composeTimeoutIDSingle(short requestID, Throwable throwable) {
		return single -> single.timeout(requestTimeoutSeconds, TimeUnit.SECONDS, Single.error(throwable))
				.observeOn(AndroidSchedulers.mainThread())
				.doOnTerminate(() -> idRequestSubjectMap.remove(requestID));
	}
	
	private <T> ObservableTransformer<T, T> composeTimeoutIDObservable(short requestID, Throwable throwable) {
		return observable -> observable.timeout(requestTimeoutSeconds, TimeUnit.SECONDS, Observable.error(throwable))
				.observeOn(AndroidSchedulers.mainThread())
				.doOnTerminate(() -> idRequestSubjectMap.remove(requestID));
	}
	
	/**
	 * Adds a {@link CompletableSubject} to the map, and takes care of timeouts and cleanup
	 * @param requestID The ID of this request
	 * @param throwable The throwable to use in case of a timeout
	 * @return A completable representing the request
	 */
	private Completable queueCompletableIDRequest(short requestID, Throwable throwable) {
		//Creating the subject
		CompletableSubject subject = CompletableSubject.create();
		
		//Adding the request to the list
		idRequestSubjectMap.put(requestID, new RequestSubject.Completable(subject, throwable));
		
		//Adding a timeout
		return subject.compose(composeTimeoutIDCompletable(requestID, throwable));
	}
	
	/**
	 * Adds a {@link SingleSubject} to the map, and takes care of timeouts and cleanup
	 * @param requestID The ID of this request
	 * @param throwable The throwable to use in case of a timeout
	 * @return A completable representing the request
	 */
	private <T> Single<T> queueSingleIDRequest(short requestID, Throwable throwable) {
		//Creating the subject
		SingleSubject<T> subject = SingleSubject.create();
		
		//Adding the request to the list
		idRequestSubjectMap.put(requestID, new RequestSubject.Single<>(subject, throwable));
		
		//Adding a timeout
		return subject.compose(composeTimeoutIDSingle(requestID, throwable));
	}
	
	/**
	 * Adds a {@link PublishSubject} to the map, and takes care of timeouts and cleanup
	 * @param requestID The ID of this request
	 * @param throwable The throwable to use in case of a timeout
	 * @return A completable representing the request
	 */
	private <T> Observable<T> queueObservableIDRequest(short requestID, Throwable throwable) {
		return queueObservableIDRequest(requestID, throwable, null);
	}
	
	/**
	 * Adds a {@link PublishSubject} to the map, and takes care of timeouts and cleanup
	 * @param requestID The ID of this request
	 * @param throwable The throwable to use in case of a timeout
	 * @param data Additional data to keep track of during the request
	 * @return A completable representing the request
	 */
	private <T> Observable<T> queueObservableIDRequest(short requestID, Throwable throwable, Object data) {
		//Creating the subject
		PublishSubject<T> subject = PublishSubject.create();
		
		//Adding the request to the list
		idRequestSubjectMap.put(requestID, new RequestSubject.Publish<>(subject, throwable, data));
		
		//Adding a timeout
		return subject.compose(composeTimeoutIDObservable(requestID, throwable));
	}
	
	/**
	 * Gets the human-readable version of the active communications protocol, or NULL if no protocol is active
	 */
	@Nullable
	public String getCommunicationsVersion() {
		if(communicationsManager != null) return communicationsManager.getCommunicationsVersion();
		else return null;
	}
	
	/**
	 * Gets if a mass retrieval is currently in progress
	 */
	public boolean isMassRetrievalInProgress() {
		return isMassRetrievalInProgress;
	}
	
	/**
	 * Gets if a sync is needed
	 */
	public boolean isPendingSync() {
		return isPendingSync;
	}
	
	/**
	 * Clears the pending sync state, for use after a sync has been initiated
	 */
	public void clearPendingSync() {
		isPendingSync = false;
	}
	
	
	/**
	 * Schedules the next keepalive ping
	 */
	private void schedulePing(Context context) {
		((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + keepAliveMillis - keepAliveWindowMillis,
				keepAliveWindowMillis * 2,
				pingPendingIntent);
	}
	
	/**
	 * Cancels the timer that sends keepalive pings
	 */
	void cancelSchedulePing(Context context) {
		((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).cancel(pingPendingIntent);
	}
	
	/**
	 * Gets the installation ID of the connected server, or NULL if unavailable
	 */
	@Nullable
	public String getServerInstallationID() {
		return serverInstallationID;
	}
	
	/**
	 * Gets the device name of the connected server, or NULL if unavailable
	 */
	@Nullable
	public String getServerDeviceName() {
		return serverDeviceName;
	}
	
	/**
	 * Gets the macOS system version of the connected server, or NULL if unavailable
	 */
	@Nullable
	public String getServerSystemVersion() {
		return serverSystemVersion;
	}
	
	/**
	 * Gets the AirMessage Server version of the connected server, or NULL if unavailable
	 */
	@Nullable
	public String getServerSoftwareVersion() {
		return serverSoftwareVersion;
	}
	
	/**
	 * Sets whether automatic reconnections should be disabled
	 */
	public void setDisableReconnections(boolean disableReconnections) {
		this.disableReconnections = disableReconnections;
		ReduxEmitterNetwork.getConnectionConfigurationSubject().onNext(disableReconnections);
	}
	
	/**
	 * Sets the override values for new connections
	 */
	public void setConnectionOverride(@Nullable ConnectionOverride<?> connectionOverride) {
		this.connectionOverride = connectionOverride;
	}
}