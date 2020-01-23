package me.tagavari.airmessage.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.RecyclerView;

import me.tagavari.airmessage.connection.ConnectionManager;
import me.tagavari.airmessage.connection.MassRetrievalParams;
import me.tagavari.airmessage.service.ConnectionService;
import me.tagavari.airmessage.messaging.ConversationInfo;
import me.tagavari.airmessage.util.ConversationUtils;
import me.tagavari.airmessage.util.Constants;
import me.tagavari.airmessage.data.DatabaseManager;
import me.tagavari.airmessage.MainApplication;
import me.tagavari.airmessage.R;
import me.tagavari.airmessage.composite.AppCompatActivityPlugin;

public class ConversationsBase extends AppCompatActivityPlugin {
	//Creating the reference values
	//static final String localBCRemoveConversation = "LocalMSG-Conversations-RemoveConversation";
	//static final String localBCPurgeConversations = "LocalMSG-Conversations-PurgeConversations";
	//static final String localBCAttachmentFragmentFailed = "LocalMSG-Conversations-Attachment-Failed";
	//static final String localBCAttachmentFragmentConfirmed = "LocalMSG-Conversations-Attachment-Confirmed";
	//static final String localBCAttachmentFragmentData = "LocalMSG-Conversations-Attachment-Data";
	//static final String localBCUpdateConversationViews = "LocalMSG-Conversations-UpdateUserViews";
	public static final String localBCConversationUpdate = "LocalMSG-Conversations-ConversationUpdate";
	
	//Creating the view values
	RecyclerView recyclerView;
	ProgressBar massRetrievalProgressBar;
	TextView noConversationsLabel;
	
	//Creating the state values
	public static final int stateIdle = 0;
	public static final int stateLoading = 1;
	public static final int stateSyncing = 2;
	public static final int stateReady = 3;
	public static final int stateLoadError = 4;
	public int currentState = stateIdle;
	public boolean conversationsAvailable = false;
	
	//Creating the listener values
	private final BroadcastReceiver massRetrievalStateBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			//Getting the state
			switch(intent.getIntExtra(Constants.intentParamState, 0)) {
				case ConnectionManager.intentExtraStateMassRetrievalStarted:
					//Setting the state to syncing
					setState(stateSyncing);
					
					break;
				case ConnectionManager.intentExtraStateMassRetrievalProgress:
					//Checking if there is a maximum value provided
					if(intent.hasExtra(Constants.intentParamSize)) {
						//Setting the progress bar's maximum
						massRetrievalProgressBar.setMax(intent.getIntExtra(Constants.intentParamSize, 0));
						
						//Setting the progress bar as determinate
						massRetrievalProgressBar.setIndeterminate(false);
					}
					
					//Setting the progress bar's progress
					if(intent.hasExtra(Constants.intentParamProgress)) {
						if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) massRetrievalProgressBar.setProgress(intent.getIntExtra(Constants.intentParamProgress, 0), true);
						else massRetrievalProgressBar.setProgress(intent.getIntExtra(Constants.intentParamProgress, 0));
					}
					
					break;
				case ConnectionManager.intentExtraStateMassRetrievalFailed:
					//Displaying a snackbar
					Snackbar.make(getActivity().findViewById(R.id.root), R.string.message_syncerror, Snackbar.LENGTH_LONG)
							.setAction(R.string.action_retry, view -> {
								//Getting the connection manager
								ConnectionManager connectionManager = ConnectionService.getConnectionManager();
								if(connectionManager == null || connectionManager.getCurrentState() != ConnectionManager.stateConnected) return;
								
								//Requesting another mass retrieval
								connectionManager.requestMassRetrieval();
							})
							.show();
					
					//Advancing the conversation state
					advanceConversationState();
					
					break;
				case ConnectionManager.intentExtraStateMassRetrievalFinished:
					//Filling the progress bar
					if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) massRetrievalProgressBar.setProgress(massRetrievalProgressBar.getMax(), true);
					else massRetrievalProgressBar.setProgress(massRetrievalProgressBar.getMax());
					
					//Advancing the conversation state
					advanceConversationState();
					
					break;
			}
		}
	};
	private final BroadcastReceiver updateConversationsBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			updateList(false);
		}
	};
	private final BroadcastReceiver contactsUpdateBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			rebuildConversationViews();
		}
	};
	
	//Creating the receiver values
	private final List<Runnable> updateListListener = new ArrayList<>();
	private final List<Runnable> conversationsLoadedListener = new ArrayList<>();
	
	//Creating the timer values
	private static final long timeUpdateHandlerDelay = 60 * 1000; //1 minute
	private Handler timeUpdateHandler = new Handler(Looper.getMainLooper());
	private Runnable timeUpdateHandlerRunnable = new Runnable() {
		@Override
		public void run() {
			//Updating the time
			if(conversations != null) for(ConversationInfo conversationInfo : conversations) conversationInfo.updateTime(getActivity());
			
			//Running again
			timeUpdateHandler.postDelayed(this, timeUpdateHandlerDelay);
		}
	};
	
	//Creating the other values
	MainApplication.LoadFlagArrayList<ConversationInfo> conversations;
	private RecyclerAdapterSource recyclerAdapterSource;
	
	ConversationsBase(RecyclerAdapterSource recyclerAdapterSource) {
		this.recyclerAdapterSource = recyclerAdapterSource;
	}
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		//Calling the super method
		super.onCreate(savedInstanceState);
		
		//Adding the broadcast listeners
		LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(getActivity());
		localBroadcastManager.registerReceiver(massRetrievalStateBroadcastReceiver, new IntentFilter(ConnectionManager.localBCMassRetrieval));
		localBroadcastManager.registerReceiver(updateConversationsBroadcastReceiver, new IntentFilter(localBCConversationUpdate));
		localBroadcastManager.registerReceiver(contactsUpdateBroadcastReceiver, new IntentFilter(MainApplication.localBCContactUpdate));
		
		//Getting the conversations
		conversations = ConversationUtils.getConversations();
		
		//Setting the conversations to an empty list if they are invalid
		if(conversations == null) {
			conversations = new MainApplication.LoadFlagArrayList<>(false);
			((MainApplication) getActivity().getApplication()).setConversations(conversations);
		}
	}
	
	private boolean isFirstOnStart = true;
	@Override
	public void onStart() {
		//Calling the super method
		super.onStart();
		
		//Advancing the conversation state (doing it in onStart so that the composite activity has time to handle its views)
		if(isFirstOnStart) {
			advanceConversationState();
			isFirstOnStart = false;
		}
	}
	
	@Override
	public void onResume() {
		//Calling the super method
		super.onResume();
		
		//Starting the time updater
		timeUpdateHandlerRunnable.run();
		timeUpdateHandler.postDelayed(timeUpdateHandlerRunnable, timeUpdateHandlerDelay);
		
		//Refreshing the list
		//updateList(false);
	}
	
	@Override
	protected void onPause() {
		//Calling the super method
		super.onPause();
		
		//Stopping the time updater
		timeUpdateHandler.removeCallbacks(timeUpdateHandlerRunnable);
	}
	
	@Override
	public void onStop() {
		//Calling the super method
		super.onStop();
	}
	
	@Override
	protected void onDestroy() {
		//Unregistering the broadcast listeners
		LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(getActivity());
		localBroadcastManager.unregisterReceiver(massRetrievalStateBroadcastReceiver);
		localBroadcastManager.unregisterReceiver(updateConversationsBroadcastReceiver);
		localBroadcastManager.unregisterReceiver(contactsUpdateBroadcastReceiver);
	}
	
	void setViews(RecyclerView recyclerView, ProgressBar massRetrievalProgressBar, TextView noConversationsLabel) {
		//Setting the views
		this.recyclerView = recyclerView;
		this.massRetrievalProgressBar = massRetrievalProgressBar;
		this.noConversationsLabel = noConversationsLabel;
	}
	
	private void advanceConversationState() {
		//Getting the connection manager
		ConnectionManager connectionManager = ConnectionService.getConnectionManager();
		
		//Checking if a mass retrieval is in progress
		if(connectionManager != null && connectionManager.isMassRetrievalInProgress()) setState(stateSyncing);
		//Otherwise checking if the conversations are loaded
		else if(conversations != null && conversations.isLoaded()) conversationLoadFinished(null);
		else {
			//Setting the state to loading
			setState(stateLoading);
			
			//Loading the messages
			new LoadConversationsTask(getActivity(), this).execute();
		}
	}
	
	private void setState(int state) {
		//Disabling the old state
		if(currentState != state) {
			switch(currentState) {
				case stateLoading: {
					View loadingText = getActivity().findViewById(R.id.loading_text);
					loadingText.animate()
							.alpha(0)
							.withEndAction(() -> loadingText.setVisibility(View.GONE));
					break;
				}
				case stateSyncing: {
					View syncView = getActivity().findViewById(R.id.syncview);
					syncView.animate()
							.alpha(0)
							.withEndAction(() -> syncView.setVisibility(View.GONE));
					break;
				}
				case stateReady: {
					recyclerView.animate()
							.alpha(0)
							.withEndAction(() -> recyclerView.setVisibility(View.GONE));
					
					View noConversations = getActivity().findViewById(R.id.no_conversations);
					if(noConversations.getVisibility() == View.VISIBLE) noConversations.animate()
							.alpha(0)
							.withEndAction(() -> noConversations.setVisibility(View.GONE));
					break;
				}
				case stateLoadError: {
					View errorView = getActivity().findViewById(R.id.errorview);
					errorView.animate()
							.alpha(0)
							.withEndAction(() -> errorView.setVisibility(View.GONE));
					break;
				}
			}
		}
		
		//Setting the new state
		currentState = state;
		
		//Enabling the new state
		switch(state) {
			case stateLoading: {
				View loadingView = getActivity().findViewById(R.id.loading_text);
				loadingView.setAlpha(0);
				loadingView.setVisibility(View.VISIBLE);
				loadingView.animate().alpha(1);
				break;
			}
			case stateSyncing: {
				View syncView = getActivity().findViewById(R.id.syncview);
				syncView.setAlpha(0);
				syncView.setVisibility(View.VISIBLE);
				syncView.animate().alpha(1);
				
				ConnectionManager connectionManager = ConnectionService.getConnectionManager();
				if(connectionManager != null) {
					if(connectionManager.isMassRetrievalWaiting()) {
						massRetrievalProgressBar.setProgress(0);
						massRetrievalProgressBar.setIndeterminate(true);
					} else {
						massRetrievalProgressBar.setMax(connectionManager.getMassRetrievalProgressCount());
						if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) massRetrievalProgressBar.setProgress(connectionManager.getMassRetrievalProgress(), true);
						else massRetrievalProgressBar.setProgress(connectionManager.getMassRetrievalProgress());
						
						massRetrievalProgressBar.setIndeterminate(false);
					}
				}
				
				break;
			}
			case stateReady:
				recyclerView.setAlpha(0);
				recyclerView.setVisibility(View.VISIBLE);
				recyclerView.animate().alpha(1);
				
				updateList(true);
				/* if(conversations.isEmpty()) {
					View noConversations = getActivity().findViewById(R.id.no_conversations);
					noConversations.animate()
							.alpha(1)
							.withStartAction(() -> noConversations.setVisibility(View.VISIBLE));
				} */
				break;
			case stateLoadError: {
				View errorView = getActivity().findViewById(R.id.errorview);
				errorView.setAlpha(0);
				errorView.setVisibility(View.VISIBLE);
				errorView.animate()
						.alpha(1);
				break;
			}
		}
	}
	
	void conversationLoadFinished(ArrayList<ConversationInfo> result) {
		//Replacing the conversations
		if(result != null) {
			conversations.clear();
			conversations.addAll(result);
			conversations.setLoaded(true);
		}
		
		//Setting the list adapter
		recyclerView.setAdapter(recyclerAdapterSource.get());
		
		//Setting the state
		setState(stateReady);
		
		//Calling the listeners
		for(Runnable listener : conversationsLoadedListener) listener.run();
	}
	
	void conversationLoadFailed() {
		//Setting the state to failed
		setState(stateLoadError);
	}
	
	static abstract class RecyclerAdapter<VH extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<VH> {
		abstract void filterAndUpdate();
		abstract boolean isListEmpty();
	}
	
	/* private class RecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		//Creating the list values
		private final List<ConversationInfo> originalItems;
		private final List<ConversationInfo> filteredItems = new ArrayList<>();
		
		//Creating the recycler values
		private RecyclerView recyclerView;
		
		RecyclerAdapter(ArrayList<ConversationInfo> items, RecyclerView recyclerView) {
			//Setting the original items
			originalItems = items;
			
			//Setting the recycler view
			this.recyclerView = recyclerView;
			
			//Filtering the data
			filterAndUpdate();
		}
		
		class ViewHolder extends RecyclerView.ViewHolder {
			ViewHolder(View itemView) {
				super(itemView);
			}
		}
		
		class ItemViewHolder extends RecyclerView.ViewHolder {
			//Creating the view values
			private final TextView contactName;
			private final TextView contactAddress;
			
			private final View header;
			private final TextView headerLabel;
			
			private final ImageView profileDefault;
			private final ImageView profileImage;
			
			private final View contentArea;
			
			private ItemViewHolder(View view) {
				//Calling the super method
				super(view);
				
				//Getting the views
				contactName = view.getActivity().findViewById(R.id.label_name);
				contactAddress = view.getActivity().findViewById(R.id.label_address);
				
				header = view.getActivity().findViewById(R.id.header);
				headerLabel = view.getActivity().findViewById(R.id.header_label);
				
				profileDefault = view.getActivity().findViewById(R.id.profile_default);
				profileImage = view.getActivity().findViewById(R.id.profile_image);
				
				contentArea = view.getActivity().findViewById(R.id.area_content);
			}
		}
		
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			//Returning the view holder
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.listitem_conversation, parent, false));
		}
		
		@Override
		public void onBindViewHolder(RecyclerView.ViewHolder viewHolder, int position) {
			//Getting the conversation info
			ConversationInfo conversationInfo = filteredItems.get(position);
			
			//Setting the view's click listener
			viewHolder.itemView.setOnClickListener(view -> {
				//Creating the intent
				Intent launchMessaging = new Intent(Conversations.this, Messaging.class);
				
				//Setting the extra
				launchMessaging.putExtra(Constants.intentParamTargetID, conversationInfo.getLocalID());
				
				//Launching the intent
				startActivity(launchMessaging);
			});
			
			//Setting the view source
			LinearLayoutManager layout = (LinearLayoutManager) recyclerView.getLayoutManager();
			conversationInfo.setViewSource(() -> layout.findViewByPosition(filteredItems.indexOf(conversationInfo)));
		}
		
		@Override
		public int getItemCount() {
			return filteredItems.size();
		}
		
		void filterAndUpdate() {
			//Clearing the filtered data
			filteredItems.clear();
			
			//Iterating over the original data
			for(ConversationInfo conversationInfo : originalItems) {
				//Skipping non-listed conversations
				if(conversationInfo.isArchived() != listingArchived) continue;
				
				//Adding the item to the filtered data
				filteredItems.add(conversationInfo);
			}
			
			//Notifying the adapter
			notifyDataSetChanged();
		}
	} */
	
	void updateList(boolean forceUpdate) {
		//Returning if the conversations aren't ready
		if(conversations == null || !conversations.isLoaded()) return;
		
		//Updating the list
		//if(sort) Collections.sort(ConversationUtils.getConversations(), ConversationUtils.conversationComparator);
		RecyclerAdapter<?> recyclerAdapter = (RecyclerAdapter<?>) recyclerView.getAdapter();
		if(recyclerAdapter == null) return;
		recyclerAdapter.filterAndUpdate();
		
		//Returning if the state is not ready
		if(currentState != stateReady) return;
		
		//Getting and checking if there are conversations
		boolean newConversationsAvailable = !recyclerAdapter.isListEmpty();
		if(forceUpdate || newConversationsAvailable != conversationsAvailable) {
			//Checking if there are conversations to display
			if(newConversationsAvailable) {
				//Hiding the label
				noConversationsLabel.animate().alpha(0).withEndAction(() -> noConversationsLabel.setVisibility(View.GONE)).start();
			} else {
				//Showing the label
				noConversationsLabel.animate().alpha(1).withStartAction(() -> {
					noConversationsLabel.setAlpha(0);
					noConversationsLabel.setVisibility(View.VISIBLE);
				}).start();
			}
			
			//Setting the new state
			conversationsAvailable = newConversationsAvailable;
		}
		
		//Calling the listeners
		for(Runnable listener : updateListListener) listener.run();
	}
	
	void rebuildConversationViews() {
		//Returning if the conversations aren't ready
		if(conversations == null || !conversations.isLoaded()) return;
		
		for(ConversationInfo conversation : conversations) {
			conversation.updateView(getActivity());
			conversation.updateViewUser(getActivity());
		}
	}
	
	void addUpdateListListener(Runnable listener) {
		updateListListener.add(listener);
	}
	
	void addConversationsLoadedListener(Runnable listener) {
		conversationsLoadedListener.add(listener);
	}
	
	//TODO implement in ViewModel
	private static class LoadConversationsTask extends AsyncTask<Void, Void, MainApplication.LoadFlagArrayList<ConversationInfo>> {
		private final WeakReference<Context> contextReference;
		private final WeakReference<ConversationsBase> superclassReference;
		
		//Creating the values
		LoadConversationsTask(Context context, ConversationsBase superclass) {
			//Setting the references
			contextReference = new WeakReference<>(context);
			superclassReference = new WeakReference<>(superclass);
		}
		
		@Override
		protected MainApplication.LoadFlagArrayList<ConversationInfo> doInBackground(Void... params) {
			//Getting the context
			Context context = contextReference.get();
			if(context == null) return null;
			
			//Loading the conversations
			return DatabaseManager.getInstance().fetchSummaryConversations(context);
		}
		
		@Override
		protected void onPostExecute(MainApplication.LoadFlagArrayList<ConversationInfo> result) {
			//Checking if the result is a fail
			if(result == null) {
				//Telling the superclass
				ConversationsBase superclass = superclassReference.get();
				if(superclass != null) superclass.conversationLoadFailed();
			} else {
				//Telling the superclass
				ConversationsBase superclass = superclassReference.get();
				if(superclass != null) {
					superclass.conversationLoadFinished(result);
				}
			}
		}
	}
	
	static class DeleteAttachmentsTask extends AsyncTask<Void, Void, Void> {
		//Creating the values
		private final WeakReference<Context> contextReference;
		
		DeleteAttachmentsTask(Context context) {
			//Setting the references
			contextReference = new WeakReference<>(context);
		}
		
		@Override
		protected Void doInBackground(Void... parameters) {
			//Getting the context
			Context context = contextReference.get();
			if(context == null) return null;
			
			//Clearing the attachment files from AM bridge
			DatabaseManager.getInstance().clearDeleteAttachmentFilesAMBridge();
			
			//Returning
			return null;
		}
	}
	
	@Deprecated
	static class DeleteMessagesTask extends AsyncTask<Void, Void, Void> {
		//Creating the values
		private final WeakReference<Context> contextReference;
		
		DeleteMessagesTask(Context context) {
			//Setting the references
			contextReference = new WeakReference<>(context);
		}
		
		@Override
		protected void onPreExecute() {
			//Getting context
			Context context = contextReference.get();
			
			//Clearing dynamic shortcuts
			if(context != null) ConversationUtils.clearDynamicShortcuts(context);
			
			//Getting the conversations
			ArrayList<ConversationInfo> conversations = ConversationUtils.getConversations();
			if(conversations != null) {
				//Disabling other shortcuts
				if(context != null) ConversationUtils.disableShortcuts(context, conversations);
				
				//Clearing the conversations in memory
				conversations.clear();
			}
			
			//Updating the conversation activity list
			if(context != null) LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(localBCConversationUpdate));
		}
		
		@Override
		protected Void doInBackground(Void... parameters) {
			//Getting the context
			Context context = contextReference.get();
			if(context == null) return null;
			
			//Removing the messages from the database
			DatabaseManager.getInstance().deleteEverything();
			
			//Clearing the attachments directory
			MainApplication.clearAttachmentsDirectory(context);
			
			//Returning
			return null;
		}
	}
	
	static class SyncMessagesTask extends AsyncTask<Void, Void, Void> {
		//Creating the values
		private final WeakReference<Context> contextReference;
		private final WeakReference<View> snackbarParentReference;
		private final MassRetrievalParams massRetrievalParams;
		
		SyncMessagesTask(Context context, View snackbarParent, MassRetrievalParams massRetrievalParams) {
			//Setting the references
			contextReference = new WeakReference<>(context);
			snackbarParentReference = new WeakReference<>(snackbarParent);
			this.massRetrievalParams = massRetrievalParams;
		}
		
		@Override
		protected void onPreExecute() {
			//Getting context
			Context context = contextReference.get();
			
			//Clearing dynamic shortcuts
			if(context != null) ConversationUtils.clearDynamicShortcuts(context);
			
			//Getting the conversations
			ArrayList<ConversationInfo> conversations = ConversationUtils.getConversations();
			if(conversations != null) {
				//Disabling other shortcuts
				if(context != null) ConversationUtils.disableShortcuts(context, conversations);
				
				//Clearing AM bridge conversations in memory
				for(ListIterator<ConversationInfo> iterator = conversations.listIterator(); iterator.hasNext();) {
					ConversationInfo conversationInfo = iterator.next();
					if(conversationInfo.getServiceHandler() == ConversationInfo.serviceHandlerAMBridge) iterator.remove();
				}
			}
			
			//Updating the conversation activity list
			if(context != null) LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(localBCConversationUpdate));
			
			//Cancelling all running tasks
			ConnectionManager manager  = ConnectionService.getConnectionManager();
			if(manager != null) manager.cancelCurrentTasks();
		}
		
		@Override
		protected Void doInBackground(Void... parameters) {
			//Getting the context
			Context context = contextReference.get();
			if(context == null) return null;
			
			//Removing the messages from the database
			DatabaseManager.getInstance().deleteEverythingAMBridge();
			
			//Clearing the attachments directory
			//MainApplication.clearAttachmentsDirectory(context);
			
			//Returning
			return null;
		}
		
		@Override
		protected void onPostExecute(Void output) {
			//Getting the connection manager
			ConnectionManager connectionManager = ConnectionService.getConnectionManager();
			boolean result;
			if(connectionManager == null) result = false;
			else {
				connectionManager.setMassRetrievalParams(massRetrievalParams);
				result = connectionManager.requestMassRetrieval();
			}
			
			//Showing a snackbar
			View parentView = snackbarParentReference.get();
			if(viewSnackbarValid(parentView)) {
				if(result) Snackbar.make(parentView, R.string.message_confirm_resyncmessages_started, Snackbar.LENGTH_SHORT).show();
				else Snackbar.make(parentView, R.string.message_serverstatus_noconnection, Snackbar.LENGTH_LONG).show();
			}
		}
		
		private boolean viewSnackbarValid(View view) {
			return view != null && view.getWindowToken() != null;
		}
	}
	
	interface RecyclerAdapterSource {
		RecyclerAdapter<?> get();
	}
}