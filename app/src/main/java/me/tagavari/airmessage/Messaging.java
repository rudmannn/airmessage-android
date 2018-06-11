package me.tagavari.airmessage;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Application;
import android.app.NotificationManager;
import android.arch.lifecycle.AndroidViewModel;
import android.arch.lifecycle.MutableLiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.ViewModel;
import android.arch.lifecycle.ViewModelProvider;
import android.arch.lifecycle.ViewModelProviders;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.constraint.ConstraintLayout;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.util.Pools;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.LinearSmoothScroller;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;
import com.crashlytics.android.Crashlytics;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import me.tagavari.airmessage.common.SharedValues;
import me.tagavari.airmessage.composite.AppCompatCompositeActivity;
import me.tagavari.airmessage.view.AppleEffectView;
import nl.dionsegijn.konfetti.KonfettiView;
import nl.dionsegijn.konfetti.models.Shape;
import nl.dionsegijn.konfetti.models.Size;

public class Messaging extends AppCompatCompositeActivity {
	//Creating the reference values
	private static final int quickScrollFABThreshold = 3;
	static final int messageChunkSize = 50;
	static final int progressiveLoadThreshold = 10;
	
	private static final int intentPickGalleryFile = 1;
	private static final int intentPickAudioFile = 2;
	private static final int intentPickDocumentFile = 3;
	private static final int intentTakePicture = 4;
	
	//Creating the static values
	private static final List<WeakReference<Messaging>> foregroundConversations = new ArrayList<>();
	private static final List<WeakReference<Messaging>> loadedConversations = new ArrayList<>();
	
	//Creating the view model and plugin values
	private ActivityViewModel viewModel;
	private PluginMessageBar pluginMessageBar;
	
	//Creating the info bar values
	private PluginMessageBar.InfoBar infoBarConnection;
	
	private boolean currentScreenEffectPlaying = false;
	
	//Creating the listener values
	private final BroadcastReceiver clientConnectionResultBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			int state = intent.getIntExtra(Constants.intentParamState, -1);
			if(state == ConnectionService.stateDisconnected) {
				int code = intent.getIntExtra(Constants.intentParamCode, -1);
				showServerWarning(code);
			} else hideServerWarning();
		}
	};
	private final RecyclerView.OnScrollListener messageListScrollListener = new RecyclerView.OnScrollListener() {
		@Override
		public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
			//Getting the layout manager
			LinearLayoutManager linearLayoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
			int itemsScrolledFromBottom = linearLayoutManager.getItemCount() - 1 - linearLayoutManager.findLastVisibleItemPosition();
			
			//Showing the FAB if the user has scrolled more than the threshold items
			if(itemsScrolledFromBottom > quickScrollFABThreshold) setFABVisibility(true);
			else setFABVisibility(false);
			
			//Marking viewed items as read
			if(itemsScrolledFromBottom < viewModel.conversationInfo.getUnreadMessageCount()) viewModel.conversationInfo.setUnreadMessageCount(itemsScrolledFromBottom);
			
			//Loading chunks if the user is scrolled to the top
			if(linearLayoutManager.findFirstVisibleItemPosition() < progressiveLoadThreshold && !viewModel.isProgressiveLoadInProgress() && !viewModel.progressiveLoadReachedLimit) recyclerView.post(viewModel::loadNextChunk);
		}
	};
	
	//Creating the view values
	private View rootView;
	private AppBarLayout appBar;
	private TextView labelLoading;
	private ViewGroup groupLoadFail;
	private TextView labelLoadFail;
	private RecyclerView messageList;
	private View inputBar;
	private ImageButton buttonSendMessage;
	private ImageButton buttonAddContent;
	private EditText messageInputField;
	private View recordingIndicator;
	private TextView recordingTimeLabel;
	private FloatingActionButton bottomFAB;
	private TextView bottomFABBadge;
	private AppleEffectView appleEffectView;
	private final HashMap<ConversationManager.MemberInfo, View> memberListViews = new HashMap<>();
	
	private View detailScrim;
	
	//Creating the other values
	private final RecyclerAdapter messageListAdapter = new RecyclerAdapter();
	private boolean messageBoxHasText = false;
	private ActivityManager.TaskDescription lastTaskDescription;
	
	private boolean toolbarVisible = true;
	
	private DialogFragment currentColorPickerDialog = null;
	private ConversationManager.MemberInfo currentColorPickerDialogMember = null;
	
	//Creating the listeners
	private final ViewTreeObserver.OnGlobalLayoutListener rootLayoutListener = () -> {
		//Getting the height
		int height = rootView.getHeight();
		
		//Checking if the window is smaller than the minimum height, the window isn't in multi-window mode
		if(height < getResources().getDimensionPixelSize(R.dimen.conversationwindow_minheight) && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode())) {
			//Hiding the app bar
			hideToolbar();
		} else {
			//Showing the app bar
			showToolbar();
		}
	};
	private final TextWatcher inputFieldTextWatcher = new TextWatcher() {
		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		
		}
		
		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {
			//Getting if the message box has text
			messageBoxHasText = stringHasChar(s.toString());
			
			//Updating the send button
			updateSendButton();
		}
		
		@Override
		public void afterTextChanged(Editable s) {
		}
	};
	private final View.OnClickListener sendButtonClickListener = view -> {
		//Returning if the input state is not text
		if(viewModel.inputState != ActivityViewModel.inputStateText) return;
		
		//Checking if the message box has text
		if(messageBoxHasText) {
			//Getting the message
			String message = messageInputField.getText().toString();
			
			//Trimming the message
			message = message.trim();
			
			//Returning if the message is empty
			if(message.isEmpty()) return;
			
			//Creating a message
			ConversationManager.MessageInfo messageInfo = new ConversationManager.MessageInfo(-1, null, viewModel.conversationInfo, null, message, null, false, System.currentTimeMillis(), SharedValues.MessageInfo.stateCodeGhost, Constants.messageErrorCodeOK, -1);
			
			//Writing the message to the database
			new AddGhostMessageTask(getApplicationContext(), messageInfo, () -> {
				//Adding the message to the conversation in memory
				viewModel.conversationInfo.addGhostMessage(this, messageInfo);
				
				//Sending the message
				messageInfo.sendMessage(this);
			}).execute();
			
			//Clearing the message box
			messageInputField.setText("");
			messageInputField.requestLayout(); //Height of input field doesn't update otherwise
			messageBoxHasText = false;
			
			//Scrolling to the bottom of the chat
			if(messageListAdapter != null) messageListAdapter.scrollToBottom();
		}
	};
	/* private final View.OnTouchListener recordingTouchListener = new View.OnTouchListener() {
		@Override
		public boolean onTouch(View view, MotionEvent motionEvent) {
			//Performing the click
			view.performClick();
			
			//Checking if the input state is content and the action is a down touch
			if(viewModel.inputState == ActivityViewModel.inputStateContent && motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
				//Attempting to start recording
				boolean result = startRecording();
				
				//Moving the recording indicator if the recording could be started
				if(result) recordingIndicator.setX(motionEvent.getRawX() - (float) recordingIndicator.getWidth() / 2f);
				
				//Returning true
				return true;
			}
			//Returning false
			return false;
		}
	}; */
	private final Observer<Byte> messagesStateObserver = state -> {
		switch(state) {
			case ActivityViewModel.messagesStateLoadingConversation:
				labelLoading.setVisibility(View.VISIBLE);
				groupLoadFail.setVisibility(View.GONE);
				
				setMessageBarState(false);
				
				break;
			case ActivityViewModel.messagesStateLoadingMessages:
				labelLoading.setVisibility(View.VISIBLE);
				groupLoadFail.setVisibility(View.GONE);
				
				setMessageBarState(false);
				
				//Setting the conversation title
				viewModel.conversationInfo.buildTitle(Messaging.this, (result, wasTasked) -> {
					getSupportActionBar().setTitle(result);
					setTaskDescription(lastTaskDescription = new ActivityManager.TaskDescription(result, BitmapFactory.decodeResource(getResources(), R.mipmap.app_icon), viewModel.conversationInfo.getConversationColor()));
				});
				
				//Coloring the UI
				colorUI(findViewById(android.R.id.content));
				
				//Setting the activity callbacks
				viewModel.conversationInfo.setActivityCallbacks(new ActivityCallbacks(this));
				
				//Setting the message input field hint
				messageInputField.setHint(getInputBarMessage());
				
				//Updating the send button
				//updateSendButton();
				
				break;
			case ActivityViewModel.messagesStateReady:
				labelLoading.setVisibility(View.GONE);
				groupLoadFail.setVisibility(View.GONE);
				
				setMessageBarState(true);
				
				//Setting the conversation title
				viewModel.conversationInfo.buildTitle(Messaging.this, (result, wasTasked) -> {
					getSupportActionBar().setTitle(result);
					setTaskDescription(lastTaskDescription = new ActivityManager.TaskDescription(result, BitmapFactory.decodeResource(getResources(), R.mipmap.app_icon), viewModel.conversationInfo.getConversationColor()));
				});
				
				//Setting the activity callbacks
				viewModel.conversationInfo.setActivityCallbacks(new ActivityCallbacks(this));
				
				//Setting the list adapter
				messageListAdapter.setItemList(viewModel.conversationItemList);
				messageList.setAdapter(messageListAdapter);
				messageList.addOnScrollListener(messageListScrollListener);
				
				//Setting the message input field hint
				messageInputField.setHint(getInputBarMessage());
				
				/* //Finding the latest send effect
				for(int i = viewModel.conversationItemList.size() - 1; i >= 0 && i >= viewModel.conversationItemList.size() - viewModel.conversationInfo.getUnreadMessageCount(); i--) {
					//Getting the conversation item
					ConversationManager.ConversationItem conversationItem = viewModel.conversationItemList.get(i);
					
					//Skipping the remainder of the iteration if the item is not a message
					if(!(conversationItem instanceof ConversationManager.MessageInfo)) continue;
					
					//Getting the message
					ConversationManager.MessageInfo messageInfo = (ConversationManager.MessageInfo) conversationItem;
					
					//Skipping the remainder of the iteration if the message has no send effect
					if(messageInfo.getSendStyle().isEmpty()) continue;
					
					//Setting the send effect
					currentScreenEffect = messageInfo.getSendStyle();
					
					//Breaking from the loop
					break;
				}
				
				//Playing the send effect if there is one
				if(!currentScreenEffect.isEmpty()) playCurrentScreenEffect(); */
				
				//Setting the last message count
				viewModel.lastUnreadCount = viewModel.conversationInfo.getUnreadMessageCount();
				
				{
					//Getting the layout manager
					LinearLayoutManager linearLayoutManager = (LinearLayoutManager) messageList.getLayoutManager();
					int itemsScrolledFromBottom = linearLayoutManager.getItemCount() - linearLayoutManager.findLastVisibleItemPosition();
					
					//Showing the FAB if the user has scrolled more than 3 items
					if(itemsScrolledFromBottom > quickScrollFABThreshold) {
						bottomFAB.show();
						restoreUnreadIndicator();
					}
				}
				
				break;
			case ActivityViewModel.messagesStateFailedConversation:
				labelLoading.setVisibility(View.GONE);
				groupLoadFail.setVisibility(View.VISIBLE);
				labelLoadFail.setText(R.string.message_loaderror_conversation);
				
				setMessageBarState(false);
				
				break;
			case ActivityViewModel.messagesStateFailedMessages:
				labelLoading.setVisibility(View.GONE);
				groupLoadFail.setVisibility(View.VISIBLE);
				labelLoadFail.setText(R.string.message_loaderror_messages);
				
				setMessageBarState(false);
				
				break;
		}
	};
	
	public Messaging() {
		//Setting the plugins;
		addPlugin(pluginMessageBar = new PluginMessageBar());
	}
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		//Calling the super method
		super.onCreate(savedInstanceState);
		
		//Setting the content view
		setContentView(R.layout.activity_messaging);
		
		//Setting the action bar
		setSupportActionBar(findViewById(R.id.toolbar));
		
		//Getting the views
		rootView = findViewById(android.R.id.content);
		appBar = findViewById(R.id.appbar);
		
		labelLoading = findViewById(R.id.loading_text);
		groupLoadFail = findViewById(R.id.group_error);
		labelLoadFail = findViewById(R.id.group_error_label);
		messageList = findViewById(R.id.list_messages);
		inputBar = findViewById(R.id.inputbar);
		buttonSendMessage = inputBar.findViewById(R.id.button_send);
		buttonAddContent = inputBar.findViewById(R.id.button_addcontent);
		messageInputField = inputBar.findViewById(R.id.messagebox);
		recordingIndicator = findViewById(R.id.recordingindicator);
		//recordingTimeLabel = inputBar.findViewById(R.id.recordingtime);
		bottomFAB = findViewById(R.id.fab_bottom);
		bottomFABBadge = findViewById(R.id.fab_bottom_badge);
		appleEffectView = findViewById(R.id.effect_foreground);
		
		detailScrim = findViewById(R.id.scrim);
		
		//Setting the plugin views
		pluginMessageBar.setParentView(findViewById(R.id.infobar_container));
		
		//Enforcing the maximum content width
		Constants.enforceContentWidth(getResources(), messageList);
		
		//Getting the conversation ID
		long conversationID = getIntent().getLongExtra(Constants.intentParamTargetID, -1);
		
		//Getting the view model
		viewModel = ViewModelProviders.of(this, new ViewModelProvider.Factory() {
			@NonNull
			@Override
			public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
				return (T) new ActivityViewModel(getApplication(), conversationID);
			}
		}).get(ActivityViewModel.class);
		
		//Registering the observers
		viewModel.messagesState.observe(this, messagesStateObserver);
		viewModel.recordingDuration.observe(this, value -> recordingTimeLabel.setText(Constants.getFormattedDuration(value)));
		viewModel.progressiveLoadInProgress.observe(this, value -> {
			if(value) onProgressiveLoadStart();
			else onProgressiveLoadFinish(viewModel.lastProgressiveLoadCount);
		});
		
		//Restoring the input bar state
		restoreInputBarState();
		
		//Restoring the text states
		//findViewById(R.id.loading_text).setVisibility(viewModel.messagesState == viewModel.messagesStateLoading ? View.VISIBLE : View.GONE);
		
		//Enabling the toolbar's up navigation
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		
		//Setting the listeners
		rootView.getViewTreeObserver().addOnGlobalLayoutListener(rootLayoutListener);
		messageInputField.addTextChangedListener(inputFieldTextWatcher);
		buttonSendMessage.setOnClickListener(sendButtonClickListener);
		buttonAddContent.setOnClickListener(view -> {
			if(viewModel.isAttachmentsPanelOpen) closeAttachmentsPanel();
			else openAttachmentsPanel(false);
		});
		/* inputBar.findViewById(R.id.button_camera).setOnClickListener(view -> requestTakePicture());
		inputBar.findViewById(R.id.button_gallery).setOnClickListener(view -> requestGalleryFile());
		inputBar.findViewById(R.id.button_attach).setOnClickListener(view -> requestAnyFile());
		contentRecordButton.setOnTouchListener(recordingTouchListener); */
		bottomFAB.setOnClickListener(view -> messageListAdapter.scrollToBottom());
		appleEffectView.setFinishListener(() -> currentScreenEffectPlaying = false);
		detailScrim.setOnClickListener(view -> closeDetailsPanel());
		
		/* //Checking if there is already a conversation info available
		if(viewModel.conversationInfo != null) {
			//Applying the conversation
			applyConversation();
		} else {
			//Getting the conversation info
			viewModel.conversationID = getIntent().getLongExtra(Constants.intentParamTargetID, -1);
			ConversationManager.ConversationInfo conversationInfo = ConversationManager.findConversationInfo(viewModel.conversationID);
			
			//Checking if the conversation info is invalid
			if(conversationInfo == null) {
				//Disabling the message bar
				setMessageBarState(false);
				
				//Showing the loading text
				findViewById(R.id.loading_text).setVisibility(View.VISIBLE);
				
				//Loading the conversation
				viewModel.loadConversation(getApplicationContext());
			} else {
				//Applying the conversation
				viewModel.conversationInfo = conversationInfo;
				applyConversation();
			}
		} */
		
		//Setting the filler data
		if(getIntent().hasExtra(Constants.intentParamDataText))
			messageInputField.setText(getIntent().getStringExtra(Constants.intentParamDataText));
		
		//Iterating over the loaded conversations
		for(Iterator<WeakReference<Messaging>> iterator = loadedConversations.iterator(); iterator.hasNext(); ) {
			//Getting the referenced activity
			Messaging activity = iterator.next().get();
			
			//Removing the reference if it is invalid
			if(activity == null) {
				iterator.remove();
				continue;
			}
			
			//Checking if the conversation matches this one
			//if(activity.viewModel.conversationID != conversationID) continue;
			
			//Destroying the activity
			activity.finish();
			iterator.remove();
			
			//Breaking from the loop
			break;
		}
		
		//Adding the conversation as a loaded conversation
		loadedConversations.add(new WeakReference<>(this));
		
		//Creating the info bars
		infoBarConnection = pluginMessageBar.create(R.drawable.disconnection, null);
		
		//Restoring the detail panel
		openDetailsPanel(true);
		
		//Updating the send button
		updateSendButton();
	}
	
	/* void onMessagesFailed() {
		//Hiding the loading text
		findViewById(R.id.loading_text).setVisibility(View.GONE);
		
		//Showing the failed text
		findViewById(R.id.error_text).setVisibility(View.VISIBLE);
	} */
	
	public void onAudioRecordingTimeUpdate(String time) {
		recordingTimeLabel.setText(time);
	}
	
	@Override
	public void onStart() {
		//Calling the super method
		super.onStart();
		
		//Adding the broadcast listeners
		LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(this);
		localBroadcastManager.registerReceiver(clientConnectionResultBroadcastReceiver, new IntentFilter(ConnectionService.localBCStateUpdate));
	}
	
	@Override
	public void onResume() {
		//Calling the super method
		super.onResume();
		
		//Adding the phantom reference
		foregroundConversations.add(new WeakReference<>(this));
		
		//Clearing the notifications
		((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel((int) viewModel.conversationID);
		
		//Updating the server warning bar state
		ConnectionService connectionService = ConnectionService.getInstance();
		boolean showWarning = connectionService == null || (connectionService.getCurrentState() == ConnectionService.stateDisconnected && ConnectionService.getLastConnectionResult() != -1);
		if(showWarning) showServerWarning(ConnectionService.getLastConnectionResult());
		else hideServerWarning();
		
		//Notifying the views
		if(viewModel.messagesState.getValue() == ActivityViewModel.messagesStateReady) {
			for(ConversationManager.ConversationItem conversationItem : viewModel.conversationItemList) {
				if(conversationItem instanceof ConversationManager.MessageInfo) {
					((ConversationManager.MessageInfo) conversationItem).notifyResume();
				}
			}
		}
		
		//Coloring the UI
		colorUI(findViewById(android.R.id.content));
	}
	
	@Override
	public void onPause() {
		//Calling the super method
		super.onPause();
		
		//Iterating over the foreground conversations
		for(Iterator<WeakReference<Messaging>> iterator = foregroundConversations.iterator(); iterator.hasNext(); ) {
			//Getting the referenced activity
			Messaging activity = iterator.next().get();
			
			//Removing the reference if it is invalid
			if(activity == null) {
				iterator.remove();
				continue;
			}
			//Skipping the remainder of the iteration if the activity isn't this one
			else if(activity != this) continue;
			
			//Removing the reference
			iterator.remove();
			
			//Breaking from the loop
			break;
		}
		
		//Notifying the views
		if(viewModel.messagesState.getValue() == ActivityViewModel.messagesStateReady) {
			for(ConversationManager.ConversationItem conversationItem : viewModel.conversationItemList) {
				if(conversationItem instanceof ConversationManager.MessageInfo) {
					((ConversationManager.MessageInfo) conversationItem).notifyPause();
				}
			}
		}
	}
	
	@Override
	public void onStop() {
		//Calling the super method
		super.onStop();
		
		//Removing the broadcast listeners
		LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(this);
		localBroadcastManager.unregisterReceiver(clientConnectionResultBroadcastReceiver);
		
		//Notifying the views
		if(viewModel.messagesState.getValue() == ActivityViewModel.messagesStateReady) {
			for(ConversationManager.ConversationItem conversationItem : viewModel.conversationItemList) {
				if(conversationItem instanceof ConversationManager.MessageInfo) {
					((ConversationManager.MessageInfo) conversationItem).notifyPause();
				}
			}
		}
	}
	
	@Override
	public void onDestroy() {
		//Calling the super method
		super.onDestroy();
		
		//Iterating over the loaded conversations
		for(Iterator<WeakReference<Messaging>> iterator = loadedConversations.iterator(); iterator.hasNext();) {
			//Getting the referenced activity
			Messaging activity = iterator.next().get();
			
			//Removing the reference if it is invalid
			if(activity == null) {
				iterator.remove();
				continue;
			}
			//Skipping the remainder of the iteration if the activity isn't this one
			else if(activity != this) continue;
			
			//Removing the reference
			iterator.remove();
			
			//Breaking from the loop
			break;
		}
		
		//Notifying the views
		if(viewModel.messagesState.getValue() == ActivityViewModel.messagesStateReady) {
			for(ConversationManager.ConversationItem conversationItem : viewModel.conversationItemList) {
				if(conversationItem instanceof ConversationManager.MessageInfo) {
					((ConversationManager.MessageInfo) conversationItem).notifyPause();
				}
			}
		}
	}
	
	long getConversationID() {
		return viewModel.conversationID;
	}
	
	private void restoreInputBarState() {
		/* switch(viewModel.inputState) {
			case ActivityViewModel.inputStateContent:
				//Disabling the message bar
				messageContentButton.setEnabled(false);
				messageInputField.setEnabled(false);
				messageSendButton.setEnabled(false);
				messageBar.getLayoutParams().height = referenceBar.getHeight();
				
				//Showing the content bar
				contentCloseButton.setRotation(45);
				contentBar.setVisibility(View.VISIBLE);
				
				break;
			case ActivityViewModel.inputStateRecording:
				//Disabling the message bar
				messageContentButton.setEnabled(false);
				messageInputField.setEnabled(false);
				messageSendButton.setEnabled(false);
				
				//Showing the recording bar
				recordingBar.setVisibility(View.VISIBLE);
				
				//Waiting until the view has been loaded
				messageList.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
					@Override
					public void onGlobalLayout() {
						//Removing the layout listener
						messageList.getViewTreeObserver().removeOnGlobalLayoutListener(this);
						
						//Positioning and showing the recording indicator
						recordingIndicator.setY(inputBar.getTop() + inputBar.getHeight() / 2 - recordingIndicator.getHeight() / 2);
						recordingIndicator.setVisibility(View.VISIBLE);
					}
				});
				
				break;
		}
		
		//Reconnecting the media player to its attachment
		//getAudioMessageManager().reconnectAttachment(conversationInfo); */
	}
	
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		//Checking if the request code is taking a picture
		if(requestCode == intentTakePicture) {
			//Returning if the current input state is not the content bar
			//if(viewModel.inputState != inputStateContent) return;
			
			//Checking if the result was a success
			if(resultCode == RESULT_OK) {
				//Sending the file
				sendFile(viewModel.targetFile);
			}
		}
		//Otherwise if the request code is the media picker
		else if(requestCode == intentPickGalleryFile || requestCode == intentPickAudioFile || requestCode == intentPickAudioFile) {
			//Returning if the current input state is not the content bar
			//if(viewModel.inputState != ActivityViewModel.inputStateContent) return;
			
			//Checking if the result was a success
			if(resultCode == RESULT_OK) {
				//Getting the content
				Uri content = data.getData();
				if(content == null) return;
				
				//Sending the file
				sendFile(content);
			}
		}
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		//Inflating the menu resource
		getMenuInflater().inflate(R.menu.menu_messaging, menu);
		
		//Returning true
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch(item.getItemId()) {
			case android.R.id.home:
				//Closing the details panel if it is open
				if(viewModel.isDetailsPanelOpen) closeDetailsPanel();
				//Otherwise finishing the activity
				else finish();
				
				return true;
			case R.id.action_details:
				//Checking if the conversation is valid
				if(viewModel.conversationInfo != null) {
					//Launching the details activity
					//startActivity(new Intent(this, MessagingInfo.class).putExtra(Constants.intentParamTargetID, viewModel.conversationInfo.getLocalID()));
					
					//Opening the details panel
					openDetailsPanel(false);
				}
				
				return true;
		}
		
		//Returning false
		return false;
	}
	
	@Override
	public void onBackPressed() {
		//Closing the details panel if it is open
		if(viewModel.isDetailsPanelOpen) closeDetailsPanel();
		//Otherwise passing the event to the superclass
		else super.onBackPressed();
	}
	
	private void openAttachmentsPanel(boolean restore) {
		//Returning if the conversation is not ready or the panel is already open
		if(viewModel.messagesState.getValue() != ActivityViewModel.messagesStateReady || (!restore && viewModel.isAttachmentsPanelOpen) || (restore && !viewModel.isAttachmentsPanelOpen)) return;
		
		//Setting the panel as open
		viewModel.isAttachmentsPanelOpen = true;
		
		//Inflating the view
		ViewStub viewStub = findViewById(R.id.viewstub_attachments);
		if(viewStub != null) {
			//Inflating the view stub
			ViewGroup inflated = (ViewGroup) viewStub.inflate();
			
			//Coloring the UI
			colorUI(inflated);
			
			//Setting up the sections
			setupAttachmentsGallerySection();
		}
		
		if(restore) {
		
		} else {
			//Animating in the panel
			int windowHeight = findViewById(android.R.id.content).getHeight();
			
			View panel = findViewById(R.id.panel_attachments);
			panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
			ValueAnimator animator = ValueAnimator.ofInt(0, panel.getMeasuredHeight());
			animator.setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime));
			animator.setInterpolator(new AccelerateDecelerateInterpolator());
			animator.addUpdateListener(animation -> {
				int value = (int) animation.getAnimatedValue();
				panel.getLayoutParams().height = value;
				panel.setY(windowHeight - value);
				panel.requestLayout();
			});
			animator.addListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationStart(Animator animation) {
					panel.setVisibility(View.VISIBLE);
				}
				
				@Override
				public void onAnimationEnd(Animator animation) {
					panel.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
					panel.requestLayout();
				}
			});
			animator.start();
		}
	}
	
	private void closeAttachmentsPanel() {
		//Returning if the conversation is not ready or the panel is already closed
		if(viewModel.conversationInfo == null || !viewModel.isAttachmentsPanelOpen) return;
		
		//Setting the panel as closed
		viewModel.isAttachmentsPanelOpen = false;
		
		//Animating out the panel
		int windowHeight = findViewById(android.R.id.content).getHeight();
		
		View panel = findViewById(R.id.panel_attachments);
		ValueAnimator animator = ValueAnimator.ofInt(panel.getHeight(), 0);
		animator.setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime));
		animator.setInterpolator(new AccelerateDecelerateInterpolator());
		animator.addUpdateListener(animation -> {
			int value = (int) animation.getAnimatedValue();
			panel.getLayoutParams().height = value;
			panel.setY(windowHeight - value);
			panel.requestLayout();
		});
		animator.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				panel.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
				panel.setVisibility(View.GONE);
			}
		});
		animator.start();
	}
	
	private void setupAttachmentsGallerySection() {
		//Getting the view
		ViewGroup viewGroup = findViewById(R.id.viewgroup_attachment_gallery);
		if(viewGroup == null) return;
		
		//Setting the click listeners
		viewGroup.findViewById(R.id.button_attachment_gallery_systempicker).setOnClickListener(view -> requestGalleryFile());
		
		//Checking if the state is failed
		if(viewModel.attachmentsMediaState == ActivityViewModel.attachmentsStateFailed) {
			//Showing the failed text
			viewGroup.findViewById(R.id.label_attachment_gallery_failed).setVisibility(View.VISIBLE);
			
			//Hiding the permission request button and the list
			viewGroup.findViewById(R.id.button_attachment_gallery_permission).setVisibility(View.GONE);
			viewGroup.findViewById(R.id.list_attachment_gallery).setVisibility(View.GONE);
		}
		//Checking if the permission has not been granted
		else if(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			//Hiding the list and failed text
			viewGroup.findViewById(R.id.list_attachment_gallery).setVisibility(View.GONE);
			viewGroup.findViewById(R.id.label_attachment_gallery_failed).setVisibility(View.GONE);
			
			//Setting up the permission request button
			View permissionButton = viewGroup.findViewById(R.id.button_attachment_gallery_permission);
			permissionButton.setVisibility(View.VISIBLE);
			permissionButton.setOnClickListener(view -> Constants.requestPermission(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, intentPickGalleryFile));
		} else {
			//Hiding the permission request button and the failed text
			viewGroup.findViewById(R.id.button_attachment_gallery_permission).setVisibility(View.GONE);
			viewGroup.findViewById(R.id.label_attachment_gallery_failed).setVisibility(View.GONE);
			
			//Setting up the list
			RecyclerView list = viewGroup.findViewById(R.id.list_attachment_gallery);
			list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
			
			//Checking if the files are loaded
			if(viewModel.attachmentsMediaState == ActivityViewModel.attachmentsStateLoaded) {
				//Setting the list adapter
				list.setAdapter(new AttachmentsGalleryRecyclerAdapter(viewModel.attachmentsMediaList));
			} else {
				//Setting the list adapter
				List<File> fileList = new ArrayList<>();
				for(int i = 0; i < ActivityViewModel.attachmentsTileCount; i++) fileList.add(null);
				list.setAdapter(new AttachmentsGalleryRecyclerAdapter(fileList));
				
				//Loading the media
				viewModel.indexAttachmentsMedia(result -> {
					if(result) {
						//Setting the list adapter's list
						((AttachmentsGalleryRecyclerAdapter) list.getAdapter()).setList(viewModel.attachmentsMediaList);
					} else {
						//Replacing the list view with the failed text
						viewGroup.findViewById(R.id.list_attachment_gallery).setVisibility(View.GONE);
						viewGroup.findViewById(R.id.label_attachment_gallery_failed).setVisibility(View.VISIBLE);
					}
				});
			}
		}
	}
	
	private void openDetailsPanel(boolean restore) {
		//Returning if the conversation is not ready or the panel is already open
		if(viewModel.messagesState.getValue() != ActivityViewModel.messagesStateReady || (!restore && viewModel.isDetailsPanelOpen) || (restore && !viewModel.isDetailsPanelOpen)) return;
		
		//Setting the panel as open
		viewModel.isDetailsPanelOpen = true;
		
		//Inflating the view
		ViewStub viewStub = findViewById(R.id.viewstub_messaginginfo);
		if(viewStub != null) {
			//Inflating the view stub
			ViewGroup inflated = (ViewGroup) viewStub.inflate();
			
			//Coloring the UI
			colorUI(inflated);
			
			//Getting the views
			Switch notificationsSwitch = inflated.findViewById(R.id.switch_getnotifications);
			//Switch pinnedSwitch = inflated.findViewById(R.id.switch_pinconversation);
			
			//Restoring the elements' states
			notificationsSwitch.setChecked(!viewModel.conversationInfo.isMuted());
			//pinnedSwitch.setChecked(viewModel.conversationInfo.isPinned());
			
			//Setting the listeners
			inflated.findViewById(R.id.group_getnotifications).setOnClickListener(view -> notificationsSwitch.setChecked(!notificationsSwitch.isChecked()));
			notificationsSwitch.setOnCheckedChangeListener((view, isChecked) -> {
				//Updating the conversation
				boolean isMuted = !isChecked;
				viewModel.conversationInfo.setMuted(isMuted);
				DatabaseManager.getInstance().updateConversationMuted(viewModel.conversationInfo.getLocalID(), isMuted);
				
			});
			//inflated.findViewById(R.id.group_pinconversation).setOnClickListener(view -> pinnedSwitch.setChecked(!pinnedSwitch.isChecked()));
			inflated.findViewById(R.id.button_changecolor).setOnClickListener(view -> showColorDialog(null, viewModel.conversationInfo.getConversationColor()));
			//pinnedSwitch.setOnCheckedChangeListener((view, isChecked) -> viewModel.conversationInfo.setPinned(isChecked));
			
			findViewById(R.id.button_archive).setOnClickListener(view -> {
				//Toggling the archive state
				boolean newState = !viewModel.conversationInfo.isArchived();
				viewModel.conversationInfo.setArchived(newState);
				
				//Sending an update
				LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ConversationsBase.localBCConversationUpdate));
				
				//Updating the conversation's database entry
				DatabaseManager.getInstance().updateConversationArchived(viewModel.conversationInfo.getLocalID(), newState);
				
				//Updating the button
				((TextView) view.findViewById(R.id.button_archive_label)).setText(newState ? R.string.action_unarchive : R.string.action_archive);
				((ImageView) view.findViewById(R.id.button_archive_icon)).setImageResource(newState ? R.drawable.unarchive : R.drawable.archive);
			});
			
			findViewById(R.id.button_delete).setOnClickListener(view -> {
				//Creating a dialog
				AlertDialog dialog = new AlertDialog.Builder(this)
						.setMessage(R.string.message_confirm_deleteconversation_current)
						.setNegativeButton(android.R.string.cancel, (dialogInterface, which) -> dialogInterface.dismiss())
						.setPositiveButton(R.string.action_delete, (dialogInterface, which) -> {
							//Removing the conversation from memory
							ArrayList<ConversationManager.ConversationInfo> conversations = ConversationManager.getConversations();
							if(conversations != null) conversations.remove(viewModel.conversationInfo);
							
							//Deleting the conversation from the database
							DatabaseManager.getInstance().deleteConversation(viewModel.conversationInfo);
							
							//Sending an update
							LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ConversationsBase.localBCConversationUpdate));
							
							//Finishing the activity
							finish();
						})
						.create();
				
				//Configuring the dialog's listener
				dialog.setOnShowListener(dialogInterface -> {
					//Setting the button's colors
					int color = getResources().getColor(R.color.colorActionDelete, null);
					dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
					dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color);
				});
				
				//Showing the dialog
				dialog.show();
			});
			
			//Adding the conversation members
			detailsBuildConversationMembers(new ArrayList<>(viewModel.conversationInfo.getConversationMembers()));
		} else {
			((ScrollView) findViewById(R.id.group_messaginginfo_scroll)).fullScroll(ScrollView.FOCUS_UP);
		}
		
		//Finding the views
		FrameLayout panel = findViewById(R.id.panel_messaginginfo);
		ViewGroup content = findViewById(R.id.group_messaginginfo_content);
		
		//Measuring the content
		content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
		
		//Comparing the content's height to the window's height
		int primaryViewHeight = findViewById(android.R.id.content).getHeight() - appBar.getHeight();
		
		//Checking if the view occupies more than 80% of the window
		if(content.getMeasuredHeight() > primaryViewHeight * 0.8F) {
			//Instructing the panel to fill the entire view
			ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) panel.getLayoutParams();
			params.topToBottom = R.id.appbar;
			params.height = 0;
		} else {
			//Instructing the panel to align to the bottom and wrap its content
			ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) panel.getLayoutParams();
			params.topToBottom = ConstraintLayout.LayoutParams.UNSET;
			params.height = ConstraintLayout.LayoutParams.WRAP_CONTENT;
		}
		
		if(restore) {
			//Configuring the views
			detailScrim.setVisibility(View.VISIBLE);
			detailScrim.setAlpha(1);
		} else {
			//Animating in the panel
			int duration = getResources().getInteger(android.R.integer.config_shortAnimTime);
			Animation animation = AnimationUtils.loadAnimation(this, R.anim.messagedetails_slide_in_bottom);
			animation.setAnimationListener(new Animation.AnimationListener() {
				@Override
				public void onAnimationStart(Animation animation) {
					panel.setVisibility(View.VISIBLE);
				}
				
				@Override
				public void onAnimationEnd(Animation animation) {}
				
				@Override
				public void onAnimationRepeat(Animation animation) {}
			});
			panel.startAnimation(animation);
			detailScrim.animate().alpha(1).withStartAction(() -> detailScrim.setVisibility(View.VISIBLE)).setDuration(duration).start();
		}
	}
	
	private void closeDetailsPanel() {
		//Returning if the conversation is not ready or the panel is already closed
		if(viewModel.conversationInfo == null || !viewModel.isDetailsPanelOpen) return;
		
		//Setting the panel as closed
		viewModel.isDetailsPanelOpen = false;
		
		//Animating out the panel
		FrameLayout panel = findViewById(R.id.panel_messaginginfo);
		Animation animation = AnimationUtils.loadAnimation(this, R.anim.messagedetails_slide_out_bottom);
		animation.setAnimationListener(new Animation.AnimationListener() {
			@Override
			public void onAnimationStart(Animation animation) {}
			
			@Override
			public void onAnimationEnd(Animation animation) {
				panel.setVisibility(View.GONE);
			}
			
			@Override
			public void onAnimationRepeat(Animation animation) {}
		});
		panel.startAnimation(animation);
		
		int duration = getResources().getInteger(android.R.integer.config_shortAnimTime);
		detailScrim.animate().alpha(0).withEndAction(() -> detailScrim.setVisibility(View.GONE)).setDuration(duration).start();
	}
	
	private void detailsBuildConversationMembers(List<ConversationManager.MemberInfo> members) {
		//Getting the members layout
		//ViewGroup membersLayout = findViewById(R.id.list_conversationmembers);
		
		//Sorting the members
		Collections.sort(members, ConversationManager.memberInfoComparator);
		
		//Adding the member views
		boolean showMemberColors = members.size() > 1;
		for(int i = 0; i < members.size(); i++) addMemberView(members.get(i), i, showMemberColors);
		/* for(final ConversationManager.MemberInfo member : members) {
			//Creating the view
			LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			View memberEntry = inflater.inflate(R.layout.listitem_member, membersLayout, false);
			
			//Setting the default information
			((TextView) memberEntry.findViewById(R.id.label_member)).setText(member.getName());
			((ImageView) memberEntry.findViewById(R.id.profile_default)).setColorFilter(member.getColor(), android.graphics.PorterDuff.Mode.MULTIPLY);
			
			//Filling in the information
			MainApplication.getInstance().getUserCacheHelper().getUserInfo(this, member.getName(), new UserCacheHelper.UserFetchResult(memberEntry) {
				@Override
				void onUserFetched(UserCacheHelper.UserInfo userInfo, boolean wasTasked) {
					//Returning if the user info is invalid
					if(userInfo == null) return;
					
					//Getting the view
					View memberEntry = viewReference.get();
					if(memberEntry == null) return;
					
					//Setting the tag
					memberEntry.setTag(userInfo.getContactLookupUri());
					
					//Setting the member's name
					((TextView) memberEntry.findViewById(R.id.label_member)).setText(userInfo.getContactName());
					TextView addressView = memberEntry.findViewById(R.id.label_address);
					addressView.setText(member.getName());
					addressView.setVisibility(View.VISIBLE);
				}
			});
			MainApplication.getInstance().getBitmapCacheHelper().assignContactImage(getApplicationContext(), member.getName(), (View) memberEntry.findViewById(R.id.profile_image));
			
			//Configuring the color editor
			ImageView changeColorButton = memberEntry.findViewById(R.id.button_change_color);
			if(members.size() == 1) {
				changeColorButton.setVisibility(View.GONE);
			} else {
				changeColorButton.setColorFilter(member.getColor(), android.graphics.PorterDuff.Mode.MULTIPLY);
				changeColorButton.setOnClickListener(view -> showColorDialog(member, member.getColor()));
				changeColorButton.setVisibility(View.VISIBLE);
			}
			
			//Setting the click listener
			memberEntry.setOnClickListener(view -> {
				//Returning if the view has no tag
				if(view.getTag() == null) return;
				
				//Opening the user's contact profile
				Intent intent = new Intent(Intent.ACTION_VIEW);
				intent.setData((Uri) view.getTag());
				//intent.setData(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, String.valueOf(view.getTag())));
				view.getContext().startActivity(intent);
			});
			
			//Adding the view
			membersLayout.addView(memberEntry);
			memberListViews.put(member.getName(), memberEntry);
		} */
	}
	
	private void addMemberView(ConversationManager.MemberInfo member, int index, boolean showColor) {
		//Getting the members layout
		ViewGroup membersLayout = findViewById(R.id.list_conversationmembers);
		if(membersLayout == null) return;
		
		//Creating the view
		LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		View memberEntry = inflater.inflate(R.layout.listitem_member, membersLayout, false);
		
		//Setting the default information
		((TextView) memberEntry.findViewById(R.id.label_member)).setText(member.getName());
		((ImageView) memberEntry.findViewById(R.id.profile_default)).setColorFilter(member.getColor(), android.graphics.PorterDuff.Mode.MULTIPLY);
		
		//Filling in the information
		MainApplication.getInstance().getUserCacheHelper().getUserInfo(this, member.getName(), new UserCacheHelper.UserFetchResult(memberEntry) {
			@Override
			void onUserFetched(UserCacheHelper.UserInfo userInfo, boolean wasTasked) {
				//Returning if the user info is invalid
				if(userInfo == null) return;
				
				//Getting the view
				View memberEntry = viewReference.get();
				if(memberEntry == null) return;
				
				//Setting the tag
				memberEntry.setTag(userInfo.getContactLookupUri());
				
				//Setting the member's name
				((TextView) memberEntry.findViewById(R.id.label_member)).setText(userInfo.getContactName());
				TextView addressView = memberEntry.findViewById(R.id.label_address);
				addressView.setText(member.getName());
				addressView.setVisibility(View.VISIBLE);
			}
		});
		MainApplication.getInstance().getBitmapCacheHelper().assignContactImage(getApplicationContext(), member.getName(), (View) memberEntry.findViewById(R.id.profile_image));
		
		//Configuring the color editor
		ImageView changeColorButton = memberEntry.findViewById(R.id.button_change_color);
		if(showColor) {
			changeColorButton.setColorFilter(member.getColor(), android.graphics.PorterDuff.Mode.MULTIPLY);
			changeColorButton.setOnClickListener(view -> showColorDialog(member, member.getColor()));
			changeColorButton.setVisibility(View.VISIBLE);
		} else {
			changeColorButton.setVisibility(View.GONE);
		}
		
		//Setting the click listener
		memberEntry.setOnClickListener(view -> {
			//Returning if the view has no tag
			if(view.getTag() == null) return;
			
			//Opening the user's contact profile
			Intent intent = new Intent(Intent.ACTION_VIEW);
			intent.setData((Uri) view.getTag());
			//intent.setData(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, String.valueOf(view.getTag())));
			view.getContext().startActivity(intent);
		});
		
		//Adding the view
		membersLayout.addView(memberEntry, index);
		memberListViews.put(member, memberEntry);
	}
	
	private void removeMemberView(ConversationManager.MemberInfo member) {
		//Getting the members layout
		ViewGroup membersLayout = findViewById(R.id.list_conversationmembers);
		if(membersLayout == null) return;
		
		//Removing the view
		membersLayout.removeView(memberListViews.get(member));
		memberListViews.remove(member);
		
		//Closing the dialog
		System.out.println("CCPD: " + (currentColorPickerDialog != null) + " / " + (currentColorPickerDialogMember == member));
		if(currentColorPickerDialog != null && currentColorPickerDialogMember == member) currentColorPickerDialog.dismiss();
	}
	
	/* @Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		//Returning if the current state isn't recording
		if(viewModel.inputState != ActivityViewModel.inputStateRecording) {
			return super.dispatchTouchEvent(event);
		}
		
		//Checking if the action is a move touch
		if(event.getAction() == MotionEvent.ACTION_MOVE) {
			//Moving the recording indicator
			recordingIndicator.setX(event.getRawX() - (float) recordingIndicator.getWidth() / 2f);
			
			//Checking if the hover values do not match
			boolean isOverDiscardZone = isPosOverDiscardZone(event.getRawX());
			if(viewModel.recordingDiscardHover != isOverDiscardZone) {
				//Getting the colors
				int colorBG = Constants.resolveColorAttr(this, android.R.attr.colorBackgroundFloating);
				int colorWarn = getResources().getColor(R.color.colorRecordingDiscard, null);
				
				ValueAnimator colorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), isOverDiscardZone ? colorBG : colorWarn, isOverDiscardZone ? colorWarn : colorBG);
				colorAnimation.setDuration(250);
				colorAnimation.addUpdateListener(animation -> recordingBar.setBackgroundColor((int) animation.getAnimatedValue()));
				colorAnimation.start();
				
				//Updating the view model's hover value
				viewModel.recordingDiscardHover = isOverDiscardZone;
			}
			
			//Returning true
			return true;
		}
		//Checking if the action is an up touch
		else if(event.getAction() == MotionEvent.ACTION_UP) {
			//Stopping the recording session
			stopRecording(isPosOverDiscardZone(event.getRawX()), (int) event.getRawX());
			
			//Returning true
			return true;
		}
		
		//Returning false
		super.dispatchTouchEvent(event);
		return false;
	} */
	
	private boolean isPosOverDiscardZone(float posX) {
		//Getting the display width
		DisplayMetrics displaymetrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
		int width = displaymetrics.widthPixels;
		
		if(getResources().getBoolean(R.bool.is_left_to_right)) return posX < (float) width * 0.2F; //Left 20% of the display
		else return posX > (float) width * 0.8F; //Right 20% of the display
	}
	
	private void colorUI(ViewGroup root) {
		//Returning if the conversation is invalid
		if(viewModel.conversationInfo == null) return;
		
		//Getting the color
		int color = viewModel.conversationInfo.getConversationColor();
		int darkerColor = ColorHelper.darkenColor(color);
		int lighterColor = ColorHelper.lightenColor(color);
		
		//Coloring the app and status bar
		getSupportActionBar().setBackgroundDrawable(new ColorDrawable(color));
		getWindow().setStatusBarColor(darkerColor);
		
		//Updating the task description
		if(lastTaskDescription != null) setTaskDescription(lastTaskDescription = new ActivityManager.TaskDescription(lastTaskDescription.getLabel(), lastTaskDescription.getIcon(), color));
		
		//Coloring tagged parts of the UI
		for(View view : Constants.getViewsByTag(root, getResources().getString(R.string.tag_primarytint))) {
			if(view instanceof ImageView) ((ImageView) view).setColorFilter(color, android.graphics.PorterDuff.Mode.MULTIPLY);
			else if(view instanceof Switch) {
				Switch switchView = (Switch) view;
				switchView.setThumbTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_checked}, new int[]{android.R.attr.state_checked}}, new int[]{0xFFFAFAFA, color}));
				switchView.setTrackTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_checked}, new int[]{android.R.attr.state_checked}}, new int[]{0x61000000, color}));
			}
			else if(view instanceof TextView) ((TextView) view).setTextColor(color);
			else if(view instanceof RelativeLayout) view.setBackground(new ColorDrawable(color));
			else if(view instanceof FrameLayout) view.setBackgroundTintList(ColorStateList.valueOf(color));
		}
		
		//Coloring the unique UI components
		if(viewModel.lastUnreadCount == 0) bottomFAB.setImageTintList(ColorStateList.valueOf(color));
		else bottomFAB.setBackgroundTintList(ColorStateList.valueOf(color));
		bottomFABBadge.setBackgroundTintList(ColorStateList.valueOf(darkerColor));
		findViewById(R.id.fab_bottom_splash).setBackgroundTintList(ColorStateList.valueOf(lighterColor));
		
		//Coloring the info bars
		infoBarConnection.setColor(color);
	}
	
	public void onClickRetryLoad(View view) {
		byte state = viewModel.messagesState.getValue();
		if(state == ActivityViewModel.messagesStateFailedConversation) viewModel.loadConversation();
		else if(state == ActivityViewModel.messagesStateFailedMessages) viewModel.loadMessages();
	}
	
	void setMessageBarState(boolean enabled) {
		//Setting the message input field
		messageInputField.setEnabled(enabled);
		
		//Setting the send button
		buttonSendMessage.setEnabled(enabled);
		//messageSendButton.setClickable(messageBoxHasText);
		//buttonSendMessage.setAlpha(enabled && messageBoxHasText ? 1 : 0.38f);
		
		//Setting the add content button
		buttonAddContent.setEnabled(enabled);
		//buttonAddContent.setAlpha(enabled ? 1 : 0.38f);
		
		//Opening the soft keyboard if the text input is enabled and the soft keyboard should be used
		//if(enabled) ((InputMethodManager) activitySource.getActivity().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(messageInputField, InputMethodManager.SHOW_FORCED);
	}
	
	private void updateSendButton() {
		//Setting the send button state
		buttonSendMessage.setClickable(messageBoxHasText);
		buttonSendMessage.setAlpha(messageBoxHasText ? 1 : 0.38f);
	}
	
	/* private boolean startRecording() {
		//Starting the recording
		if(!viewModel.startRecording(this)) return false;
		
		//Resetting the recording bar's color
		//recordingBar.setBackgroundColor(Constants.resolveColorAttr(this, android.R.attr.colorBackgroundFloating));
		
		//Setting the recording indicator's Y
		recordingIndicator.setY(inputBar.getTop() + inputBar.getHeight() / 2 - recordingIndicator.getHeight() / 2);
		
		//Revealing the indicator
		recordingIndicator.setVisibility(View.VISIBLE);
		Animator animator = ViewAnimationUtils.createCircularReveal(recordingIndicator, recordingIndicator.getLeft() + recordingIndicator.getWidth() / 2, recordingIndicator.getTop() + recordingIndicator.getWidth() / 2, 0, recordingIndicator.getWidth());
		animator.setInterpolator(new AccelerateDecelerateInterpolator());
		animator.start();
		
		//Showing the recording input
		//showRecordingBar();
		
		//Returning true
		return true;
	}
	
	void stopRecording(boolean forceDiscard, int positionX) {
		//Returning if the input state is not recording
		if(viewModel.inputState != ActivityViewModel.inputStateRecording) return;
		
		//Stopping the recording session
		boolean fileAvailable = viewModel.stopRecording(forceDiscard);
		
		//Concealing the recording indicator
		Animator animator = ViewAnimationUtils.createCircularReveal(recordingIndicator, recordingIndicator.getLeft() + recordingIndicator.getWidth() / 2, recordingIndicator.getTop() + recordingIndicator.getWidth() / 2, recordingIndicator.getWidth(), 0);
		animator.setInterpolator(new AccelerateDecelerateInterpolator());
		animator.setStartDelay(100);
		animator.addListener(new Animator.AnimatorListener() {
			@Override
			public void onAnimationStart(Animator animation) {
			
			}
			
			@Override
			public void onAnimationEnd(Animator animation) {
				//Returning if the state doesn't match
				if(viewModel.inputState == ActivityViewModel.inputStateRecording) return;
				
				//Hiding the recording indicator
				recordingIndicator.setVisibility(View.INVISIBLE);
			}
			
			@Override
			public void onAnimationCancel(Animator animation) {
			
			}
			
			@Override
			public void onAnimationRepeat(Animator animation) {
			
			}
		});
		animator.start();
		
		//Hiding the recording bar
		//int[] recordingButtonLocation = {0, 0};
		//contentRecordButton.getLocationOnScreen(recordingButtonLocation);
		//hideRecordingBar(fileAvailable, recordingButtonLocation[0]);
		hideRecordingBar(fileAvailable, positionX);
		
		//Sending the file
		if(fileAvailable) sendFile(viewModel.targetFile);
	} */
	
	private String getInputBarMessage() {
		//Returning a generic message if the service is invalid
		if(viewModel.conversationInfo.getService() == null)
			return getResources().getString(R.string.imperative_messageinput);
		
		switch(viewModel.conversationInfo.getService()) {
			case Constants.serviceIDAppleMessage:
				return getResources().getString(R.string.proper_imessage);
			case Constants.serviceIDSMS:
				return getResources().getString(R.string.proper_sms);
			default:
				return viewModel.conversationInfo.getService();
		}
	}
	
	void showServerWarning(int reason) {
		switch(reason) {
			case ConnectionService.intentResultCodeInternalException:
				infoBarConnection.setText(R.string.message_serverstatus_internalexception);
				infoBarConnection.setButton(R.string.action_retry, view -> {
					ConnectionService connectionService = ConnectionService.getInstance();
					if(connectionService == null) {
						//Starting the service
						startService(new Intent(Messaging.this, ConnectionService.class));
					} else {
						//Reconnecting
						connectionService.reconnect();
						
						//Hiding the bar
						hideServerWarning();
					}
				});
				break;
			case ConnectionService.intentResultCodeBadRequest:
				infoBarConnection.setText(R.string.message_serverstatus_badrequest);
				infoBarConnection.setButton(R.string.action_retry, view -> {
					ConnectionService connectionService = ConnectionService.getInstance();
					if(connectionService == null) {
						//Starting the service
						startService(new Intent(Messaging.this, ConnectionService.class));
					} else {
						//Reconnecting
						connectionService.reconnect();
						
						//Hiding the bar
						hideServerWarning();
					}
				});
				break;
			case ConnectionService.intentResultCodeClientOutdated:
				infoBarConnection.setText(R.string.message_serverstatus_clientoutdated);
				infoBarConnection.setButton(R.string.action_update, view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getPackageName()))));
				break;
			case ConnectionService.intentResultCodeServerOutdated:
				infoBarConnection.setText(R.string.message_serverstatus_serveroutdated);
				infoBarConnection.setButton(R.string.screen_help, view -> startActivity(new Intent(Intent.ACTION_VIEW, Constants.serverUpdateAddress)));
				break;
			case ConnectionService.intentResultCodeUnauthorized:
				infoBarConnection.setText(R.string.message_serverstatus_authfail);
				infoBarConnection.setButton(R.string.action_reconfigure, view -> startActivity(new Intent(Messaging.this, ServerSetup.class)));
				break;
			case ConnectionService.intentResultCodeConnection:
				infoBarConnection.setText(R.string.message_serverstatus_noconnection);
				infoBarConnection.setButton(R.string.action_retry, view -> {
					ConnectionService connectionService = ConnectionService.getInstance();
					if(connectionService == null) {
						//Starting the service
						startService(new Intent(Messaging.this, ConnectionService.class));
					} else {
						//Reconnecting
						connectionService.reconnect();
						
						//Hiding the bar
						hideServerWarning();
					}
				});
				break;
			default:
				infoBarConnection.setText(R.string.message_serverstatus_unknown);
				infoBarConnection.setButton(R.string.action_retry, view -> {
					ConnectionService connectionService = ConnectionService.getInstance();
					if(connectionService == null) {
						//Starting the service
						startService(new Intent(Messaging.this, ConnectionService.class));
					} else {
						//Reconnecting
						connectionService.reconnect();
						
						//Hiding the bar
						hideServerWarning();
					}
				});
				break;
		}
		
		//Showing the info bar
		infoBarConnection.show();
	}
	
	void hideServerWarning() {
		infoBarConnection.hide();
	}
	
	void hideToolbar() {
		//Returning if the toolbar is already invisible
		if(!toolbarVisible) return;
		
		//Setting the toolbar as invisible
		toolbarVisible = false;
		
		//Hiding the app bar
		appBar.setVisibility(View.GONE);
	}
	
	void showToolbar() {
		//Returning if the toolbar is already visible
		if(toolbarVisible) return;
		
		//Setting the toolbar as visible
		toolbarVisible = true;
		
		//Showing the app bar
		appBar.setVisibility(View.VISIBLE);
	}
	
	void setFABVisibility(boolean visible) {
		//Returning if the current state matches the requested state
		if(bottomFAB.isShown() == visible) return;
		
		if(visible) {
			//Showing the FAB
			bottomFAB.show();
			
			//Checking if there are unread messages
			if(viewModel.conversationInfo.getUnreadMessageCount() > 0) {
				//Animating the badge (to go with the FAB appearance)
				bottomFABBadge.setVisibility(View.VISIBLE);
				bottomFABBadge.animate()
						.scaleX(1)
						.scaleY(1)
						.setInterpolator(new OvershootInterpolator())
						.start();
			}
		} else {
			//Hiding the FAB
			bottomFAB.hide();
			
			//Checking if there are unread messages
			if(viewModel.conversationInfo.getUnreadMessageCount() > 0) {
				//Hiding the badge (to go with the FAB disappearance)
				bottomFABBadge.animate()
						.scaleX(0)
						.scaleY(0)
						.withEndAction(() -> bottomFABBadge.setVisibility(View.GONE))
						.start();
			}
		}
	}
	
	void restoreUnreadIndicator() {
		//Returning if the indicator shouldn't be visible
		if(viewModel.conversationInfo.getUnreadMessageCount() == 0) return;
		
		//Coloring the FAB
		bottomFAB.setBackgroundTintList(ColorStateList.valueOf(viewModel.conversationInfo.getConversationColor()));
		bottomFAB.setImageTintList(ColorStateList.valueOf(getResources().getColor(android.R.color.white, null)));
		
		//Updating the badge
		bottomFABBadge.setText(String.format(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? getResources().getConfiguration().getLocales().get(0) : getResources().getConfiguration().locale, "%d", viewModel.conversationInfo.getUnreadMessageCount()));
		bottomFABBadge.setVisibility(View.VISIBLE);
		bottomFABBadge.setScaleX(1);
		bottomFABBadge.setScaleY(1);
	}
	
	void updateUnreadIndicator() {
		//Returning if the value has not changed
		if(viewModel.lastUnreadCount == viewModel.conversationInfo.getUnreadMessageCount()) return;
		
		//Getting the color
		int colorTint = viewModel.conversationInfo.getConversationColor();
		
		//Checking if there are any unread messages
		if(viewModel.conversationInfo.getUnreadMessageCount() > 0) {
			//Coloring the FAB
			bottomFAB.setBackgroundTintList(ColorStateList.valueOf(viewModel.conversationInfo.getConversationColor()));
			bottomFAB.setImageTintList(ColorStateList.valueOf(getResources().getColor(android.R.color.white, null)));
			
			//Updating the badge text
			bottomFABBadge.setText(String.format(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? getResources().getConfiguration().getLocales().get(0) : getResources().getConfiguration().locale, "%d", viewModel.conversationInfo.getUnreadMessageCount()));
			
			//Animating the badge
			if(bottomFAB.isShown()) {
				bottomFABBadge.setVisibility(View.VISIBLE);
				bottomFABBadge.setScaleX(0);
				bottomFABBadge.setScaleY(0);
				bottomFABBadge.animate()
						.scaleX(1)
						.scaleY(1)
						.setInterpolator(new OvershootInterpolator())
						.start();
			}
			
			//Checking if there were previously no unread messages and the FAB is visible
			if(viewModel.lastUnreadCount == 0 && bottomFAB.isShown()) {
				//Animating the splash
				View bottomSplash = findViewById(R.id.fab_bottom_splash);
				bottomSplash.setScaleX(1);
				bottomSplash.setScaleY(1);
				bottomSplash.setAlpha(1);
				bottomSplash.setVisibility(View.VISIBLE);
				bottomSplash.animate()
						.scaleX(2.5F)
						.scaleY(2.5F)
						.alpha(0)
						.setDuration(1000)
						.setInterpolator(new DecelerateInterpolator())
						.withEndAction(() -> bottomSplash.setVisibility(View.GONE))
						.start();
			}
		} else {
			//Restoring the FAB color
			bottomFAB.setBackgroundTintList(ColorStateList.valueOf(Constants.resolveColorAttr(this, android.R.attr.colorBackgroundFloating)));
			bottomFAB.setImageTintList(ColorStateList.valueOf(colorTint));
			
			//Hiding the badge
			if(bottomFAB.isShown()) bottomFABBadge.animate()
					.scaleX(0)
					.scaleY(0)
					.withEndAction(() -> bottomFABBadge.setVisibility(View.GONE))
					.start();
		}
		
		//Setting the last unread message count
		viewModel.lastUnreadCount = viewModel.conversationInfo.getUnreadMessageCount();
	}
	
	@Override
	public void onRequestPermissionsResult(final int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		//Returning if there are no grant results
		if(grantResults.length == 0) return;
		
		//Checking if the request code is recording audio
		if(requestCode == Constants.permissionRecordAudio) {
			//Checking if the result is a denial
			if(grantResults[0] == PackageManager.PERMISSION_DENIED) {
				//Creating a dialog
				AlertDialog dialog = new AlertDialog.Builder(Messaging.this)
						.setTitle(R.string.message_permissionrejected)
						.setMessage(R.string.message_permissiondetails_microphone_failedrequest)
						.setPositiveButton(R.string.action_retry, (dialogInterface, which) -> {
							//Requesting microphone access
							Constants.requestPermission(Messaging.this, new String[]{Manifest.permission.RECORD_AUDIO}, Constants.permissionRecordAudio);
							
							//Dismissing the dialog
							dialogInterface.dismiss();
						})
						.setNeutralButton(R.string.screen_settings, (dialogInterface, which) -> {
							//Showing the application settings
							Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
							intent.setData(Uri.parse("package:" + getPackageName()));
							startActivity(intent);
							
							//Dismissing the dialog
							dialogInterface.dismiss();
						})
						.setNegativeButton(android.R.string.cancel, (dialogInterface, which) -> dialogInterface.dismiss())
						.create();
				
				//Configuring the dialog's listener
				dialog.setOnShowListener(dialogInterface -> {
					//Setting the button's colors
					int color = viewModel.conversationInfo.getConversationColor();
					dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
					dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(color);
					dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color);
				});
				
				//Displaying the dialog
				dialog.show();
			}
		}
	}
	
	private void requestTakePicture() {
		//Creating the intent
		Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
		
		//Checking if there are no apps that can take the intent
		if(takePictureIntent.resolveActivity(getPackageManager()) == null) {
			//Telling the user via a toast
			Toast.makeText(Messaging.this, R.string.message_intenterror_camera, Toast.LENGTH_SHORT).show();
			
			//Returning
			return;
		}
		
		//Finding a free file
		viewModel.targetFile = MainApplication.findUploadFileTarget(this, Constants.pictureName);
		
		try {
			//Creating the targets
			if(!viewModel.targetFile.getParentFile().mkdir()) throw new IOException();
			//if(!viewModel.targetFile.createNewFile()) throw new IOException();
		} catch(IOException exception) {
			//Printing the stack trace
			exception.printStackTrace();
			
			//Returning
			return;
		}
		
		//Getting the content uri
		Uri imageUri = FileProvider.getUriForFile(this, MainApplication.fileAuthority, viewModel.targetFile);
		
		//Setting the clip data
		takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
		
		//Setting the file path (for future reference)
		//takePictureIntent.putExtra(Constants.intentParamDataFile, viewModel.targetFile);
		
		//Starting the activity
		startActivityForResult(takePictureIntent, intentTakePicture);
	}
	
	private void requestGalleryFile() {
		//Launching the system's file picker
		Intent intent = new Intent();
		intent.setType("*/*");
		String[] mimeTypes = {"image/*", "video/*"};
		intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
		//intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
		intent.setAction(Intent.ACTION_GET_CONTENT);
		startActivityForResult(Intent.createChooser(intent, getResources().getString(R.string.imperative_selectfile)), intentPickGalleryFile);
	}
	
	private void requestAnyFile() {
		//Launching the system's file picker
		Intent intent = new Intent();
		intent.setType("*/*");
		//String[] mimeTypes = {"image/*", "video/*"};
		//intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
		//intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
		intent.setAction(Intent.ACTION_GET_CONTENT);
		startActivityForResult(Intent.createChooser(intent, getResources().getString(R.string.imperative_selectfile)), intentPickDocumentFile);
	}
	
	private static boolean stringHasChar(String string) {
		//Looping through the characters of the string
		for(char character : string.toCharArray()) {
			//Returning true if the character is not a control character
			if(!Character.isISOControl(character) && !Character.isSpaceChar(character))
				return true;
		}
		
		//Returning false
		return false;
	}
	
	public static ArrayList<Long> getForegroundConversations() {
		//Creating the list
		ArrayList<Long> list = new ArrayList<>();
		
		//Iterating over the loaded conversations
		for(Iterator<WeakReference<Messaging>> iterator = foregroundConversations.iterator(); iterator.hasNext(); ) {
			//Getting the referenced activity
			Messaging activity = iterator.next().get();
			
			//Removing the reference if it is invalid
			if(activity == null) {
				iterator.remove();
				continue;
			}
			
			//Adding the entry to the list
			list.add(activity.getConversationID());
		}
		
		//Returning the list
		return list;
	}
	
	public static ArrayList<Long> getLoadedConversations() {
		//Creating the list
		ArrayList<Long> list = new ArrayList<>();
		
		//Iterating over the loaded conversations
		for(Iterator<WeakReference<Messaging>> iterator = loadedConversations.iterator(); iterator.hasNext(); ) {
			//Getting the referenced activity
			Messaging activity = iterator.next().get();
			
			//Removing the reference if it is invalid
			if(activity == null) {
				iterator.remove();
				continue;
			}
			
			//Adding the entry to the list
			list.add(activity.getConversationID());
		}
		
		//Returning the list
		return list;
	}
	
	private void sendFile(File file) {
		//Creating a message
		ConversationManager.MessageInfo messageInfo = new ConversationManager.MessageInfo(-1, null, viewModel.conversationInfo, null, null, null, false, System.currentTimeMillis(), SharedValues.MessageInfo.stateCodeGhost, Constants.messageErrorCodeOK, -1);
		
		//Starting the task
		new SendFilePreparationTask(this, messageInfo, file).execute();
	}
	
	private void sendFile(Uri uri) {
		//Creating a message
		ConversationManager.MessageInfo messageInfo = new ConversationManager.MessageInfo(-1, null, viewModel.conversationInfo, null, null, null, false, System.currentTimeMillis(), SharedValues.MessageInfo.stateCodeGhost, Constants.messageErrorCodeOK, -1);
		
		//Starting the task
		new SendFilePreparationTask(this, messageInfo, uri).execute();
	}
	
	private static class SendFilePreparationTask extends AsyncTask<Void, Void, Void> {
		//Creating the reference values
		private final WeakReference<Messaging> activityReference;
		private final WeakReference<Context> contextReference;
		
		//Creating the request values
		private final ConversationManager.MessageInfo messageInfo;
		private File targetFile;
		private Uri targetUri;
		
		private SendFilePreparationTask(Messaging activity, ConversationManager.MessageInfo messageInfo) {
			//Setting the references
			activityReference = new WeakReference<>(activity);
			contextReference = new WeakReference<>(activity.getApplicationContext());
			
			//Setting the basic values
			this.messageInfo = messageInfo;
		}
		
		SendFilePreparationTask(Messaging activity, ConversationManager.MessageInfo messageInfo, File file) {
			//Calling the main constructor
			this(activity, messageInfo);
			
			//Setting the request values
			targetFile = file;
			targetUri = null;
		}
		
		SendFilePreparationTask(Messaging activity, ConversationManager.MessageInfo messageInfo, Uri uri) {
			//Calling the main constructor
			this(activity, messageInfo);
			
			//Setting the request values
			targetFile = null;
			targetUri = uri;
		}
		
		@Override
		protected Void doInBackground(Void... parameters) {
			//Getting the context
			Context context = contextReference.get();
			if(context == null) return null;
			
			//Creating the attachment
			ConversationManager.AttachmentInfo attachment;
			if(targetFile != null) attachment = ConversationManager.createAttachmentInfoFromType(-1, null, messageInfo, targetFile.getName(), Constants.getMimeType(targetFile), targetFile);
			else attachment = ConversationManager.createAttachmentInfoFromType(-1, null, messageInfo, Constants.getFileName(context, targetUri), Constants.getMimeType(context, targetUri), targetUri);
			
			//Adding the item to the database
			messageInfo.addAttachment(attachment);
			DatabaseManager.getInstance().addConversationItem(messageInfo);
			
			//Returning
			return null;
		}
		
		@Override
		protected void onPostExecute(Void parameter) {
			//Getting the context
			Context context = contextReference.get();
			if(context != null) {
				//Checking if the conversation is loaded
				long currentConversationID = messageInfo.getConversationInfo().getLocalID();
				boolean conversationFound = false;
				for(long identifiers : Messaging.getForegroundConversations())
					if(currentConversationID == identifiers) {
						conversationFound = true;
						break;
					}
				
				//Checking if the conversation is still loaded
				if(conversationFound) {
					//Adding the message to the conversation in memory
					messageInfo.getConversationInfo().addGhostMessage(context, messageInfo);
					
					//Getting the activity
					Messaging activity = activityReference.get();
					if(activity != null) {
						//Scrolling to the bottom of the chat
						activity.messageListAdapter.scrollToBottom();
					}
				}
			}
			
			//Sending the message
			Activity activity = activityReference.get();
			if(activity != null) messageInfo.sendMessage(activity);
		}
	}
	
	private static final long confettiDuration = 1000;
	
	void playScreenEffect(String effect, View target) {
		//Returning if an effect is already playing
		if(currentScreenEffectPlaying) return;
		currentScreenEffectPlaying = true;
		
		switch(effect) {
			case Constants.appleSendStyleScrnEcho:
				//Activating the effect view
				appleEffectView.playEcho(target);
				break;
			case Constants.appleSendStyleScrnSpotlight:
				currentScreenEffectPlaying = false;
				break;
			case Constants.appleSendStyleScrnBalloons:
				appleEffectView.playBalloons();
				break;
			case Constants.appleSendStyleScrnConfetti: {
				//Activating the Konfetti view
				KonfettiView konfettiView = findViewById(R.id.konfetti);
				konfettiView.build()
						.addColors(Constants.effectColors)
						.setDirection(0D, 359D)
						.setSpeed(4F, 8F)
						.setFadeOutEnabled(true)
						.setTimeToLive(5000L)
						.addShapes(Shape.RECT, Shape.CIRCLE)
						.addSizes(new Size(12, 5), new Size(16, 6))
						.setPosition(-50F, konfettiView.getWidth() + 50F, -50F, -50F)
						.streamFor(300, confettiDuration);
				
				//Setting the timer to mark the effect as finished
				new Handler().postDelayed(() -> currentScreenEffectPlaying = false, confettiDuration * 5);
				
				break;
			}
			case Constants.appleSendStyleScrnLove:
				currentScreenEffectPlaying = false;
				break;
			case Constants.appleSendStyleScrnLasers:
				currentScreenEffectPlaying = false;
				break;
			case Constants.appleSendStyleScrnFireworks:
				currentScreenEffectPlaying = false;
				break;
			case Constants.appleSendStyleScrnShootingStar:
				currentScreenEffectPlaying = false;
				break;
			case Constants.appleSendStyleScrnCelebration:
				currentScreenEffectPlaying = false;
				break;
			default:
				currentScreenEffectPlaying = false;
				break;
		}
	}
	
	void onProgressiveLoadStart() {
		//Updating the recycler adapter (to show the loading spinner)
		messageListAdapter.notifyItemInserted(0);
	}
	
	void onProgressiveLoadFinish(int itemCount) {
		messageListAdapter.notifyItemRemoved(0); //Removing the loading spinner
		messageListAdapter.notifyItemRangeInserted(0, itemCount); //Inserting the new items
	}
	
	class RecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		//Creating the reference values
		private static final int itemTypeLoadingBar = -1;
		
		//Creating the values
		private ArrayList<ConversationManager.ConversationItem> conversationItems = null;
		private RecyclerView recyclerView;
		
		//Creating the pools
		private final SparseArray<Pools.SimplePool<? extends RecyclerView.ViewHolder>> componentPoolList = new SparseArray<>();
		private final PoolSource poolSource = new PoolSource();
		
		RecyclerAdapter() {
			//Enabling stable IDs
			setHasStableIds(true);
		}
		
		void setItemList(ArrayList<ConversationManager.ConversationItem> items) {
			//Setting the conversation items
			conversationItems = items;
			
			//Updating the view
			//messageList.getRecycledViewPool().clear();
			notifyDataSetChanged();
		}
		
		@Override
		public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
			super.onAttachedToRecyclerView(recyclerView);
			
			this.recyclerView = recyclerView;
		}
		
		@Override
		@NonNull
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			//Returning the correct view holder
			switch(viewType) {
				case ConversationManager.ConversationItem.viewTypeMessage:
					return new ConversationManager.MessageInfo.ViewHolder(LayoutInflater.from(Messaging.this).inflate(R.layout.listitem_messageimp, parent, false));
				case ConversationManager.ConversationItem.viewTypeAction:
					return new ConversationManager.ActionLineViewHolder(LayoutInflater.from(Messaging.this).inflate(R.layout.listitem_action, parent, false));
				case itemTypeLoadingBar: {
					View loadingView = LayoutInflater.from(Messaging.this).inflate(R.layout.listitem_loading, parent, false);
					((ProgressBar) loadingView.findViewById(R.id.progressbar)).setIndeterminateTintList(ColorStateList.valueOf(viewModel.conversationInfo.getConversationColor()));
					return new LoadingViewHolder(loadingView);
				}
				default:
					throw new IllegalArgumentException();
			}
		}
		
		@Override
		public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
			if(holder instanceof ConversationManager.MessageInfo.ViewHolder) {
				for(ConversationManager.MessageComponent.ViewHolder attachmentHolder : ((ConversationManager.MessageInfo.ViewHolder) holder).messageComponents) {
					attachmentHolder.cleanupState();
				}
			}
		}
		
		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			//Returning if there are no items or the item is the loading spinner
			if(conversationItems == null || getItemViewType(position) == itemTypeLoadingBar) return;
			
			//Getting the item
			ConversationManager.ConversationItem conversationItem;
			if(viewModel.isProgressiveLoadInProgress()) conversationItem = conversationItems.get(position - 1);
			else conversationItem = conversationItems.get(position);
			
			//Checking if the item is a message
			boolean isMessage = false;
			if(conversationItem instanceof ConversationManager.MessageInfo) {
				((ConversationManager.MessageInfo.ViewHolder) holder).setPoolSource(poolSource);
				isMessage = true;
			}
			
			//Creating the view
			conversationItem.bindView(holder, Messaging.this);
			
			//Setting the view source
			conversationItem.setViewHolderSource(new Constants.ViewHolderSourceImpl<RecyclerView.ViewHolder>(recyclerView, conversationItem.getLocalID()));
			
			//Checking if the item is a message
			if(isMessage) {
				ConversationManager.MessageInfo messageInfo = (ConversationManager.MessageInfo) conversationItem;
				//Playing the message's effect if it hasn't been viewed yet
				if(messageInfo.getSendStyle() != null && !messageInfo.getSendStyleViewed()) {
					messageInfo.setSendStyleViewed(true);
					messageInfo.playEffect((ConversationManager.MessageInfo.ViewHolder) holder);
					/* if(Constants.validateScreenEffect(messageInfo.getSendStyle())) playScreenEffect(messageInfo.getSendStyle());
					else messageInfo.playEffect(); */
					new TaskMarkMessageSendStyleViewed().execute(messageInfo);
				}
			}
		}
		
		@Override
		public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
			//Clearing the view's animation
			holder.itemView.clearAnimation();
		}
		
		@Override
		public int getItemCount() {
			if(conversationItems == null) return 0;
			int size = conversationItems.size();
			if(viewModel.isProgressiveLoadInProgress()) size += 1;
			return size;
		}
		
		@Override
		public int getItemViewType(int position) {
			if(conversationItems == null) return 0;
			if(isViewLoadingSpinner(position)) return itemTypeLoadingBar;
			return conversationItems.get(position - (viewModel.isProgressiveLoadInProgress() ? 1 : 0)).getItemViewType();
		}
		
		@Override
		public long getItemId(int position) {
			if(conversationItems == null) return 0;
			if(isViewLoadingSpinner(position)) return -1;
			return conversationItems.get(position - (viewModel.isProgressiveLoadInProgress() ? 1 : 0)).getLocalID();
		}
		
		private boolean isViewLoadingSpinner(int position) {
			return viewModel.isProgressiveLoadInProgress() && position == 0;
		}
		
		final class PoolSource {
			static final int poolSize = 12;
			
			ConversationManager.MessageComponent.ViewHolder getComponent(ConversationManager.MessageComponent<ConversationManager.MessageComponent.ViewHolder> component, ViewGroup parent, Context context) {
				Pools.SimplePool<ConversationManager.MessageComponent.ViewHolder> pool = (Pools.SimplePool<ConversationManager.MessageComponent.ViewHolder>) componentPoolList.get(component.getItemViewType());
				if(pool == null) return component.createViewHolder(context, parent);
				else {
					ConversationManager.MessageComponent.ViewHolder viewHolder = pool.acquire();
					return viewHolder == null ? component.createViewHolder(context, parent) : viewHolder;
				}
			}
			
			void releaseComponent(int componentViewType, ConversationManager.MessageComponent.ViewHolder viewHolder) {
				Pools.SimplePool<ConversationManager.MessageComponent.ViewHolder> pool = (Pools.SimplePool<ConversationManager.MessageComponent.ViewHolder>) componentPoolList.get(componentViewType);
				if(pool == null) {
					pool = new Pools.SimplePool<>(poolSize);
					componentPoolList.put(componentViewType, pool);
				}
				pool.release(viewHolder);
			}
		}
		
		/* void scrollNotifyItemInserted(int position) {
			//Returning if the list is empty
			if(conversationItems.isEmpty()) return;
			
			//Getting if the list is currently scrolled to the bottom
			boolean scrolledToBottom = ((LinearLayoutManager) recyclerView.getLayoutManager()).findLastCompletelyVisibleItemPosition() == getItemCount() - 2; //-2 because the item was already added to the list
			
			//Calling the original method
			notifyItemInserted(position);
			
			//Checking if the list is scrolled to the end and the new item is at the bottom
			if(scrolledToBottom && position == getItemCount() - 1) {
				//Scrolling to the bottom
				recyclerView.smoothScrollToPosition(getItemCount() - 1);
			}
		} */
		
		boolean isScrolledToBottom() {
			return ((LinearLayoutManager) recyclerView.getLayoutManager()).findLastCompletelyVisibleItemPosition() == getItemCount() - 1;
		}
		
		boolean isDirectlyBelowFrame(int index) {
			return ((LinearLayoutManager) recyclerView.getLayoutManager()).findLastCompletelyVisibleItemPosition() + 1 == index;
		}
		
		void scrollToBottom() {
			//Returning if the list has already been scrolled to the bottom
			if(isScrolledToBottom()) return;
			
			//Scrolling to the bottom
			recyclerView.smoothScrollToPosition(getItemCount() - 1);
		}
	}
	
	private class AttachmentsGalleryRecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		//Creating the reference values
		private static final int itemTypeActionButton = 0;
		private static final int itemTypeContent = 1;
		private static final int itemTypeOverflowButton = 2;
		
		//Creating the list values
		private List<File> fileList;
		
		AttachmentsGalleryRecyclerAdapter(List<File> list) {
			fileList = list;
		}
		
		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			switch(viewType) {
				case itemTypeActionButton: {
					View actionButton = getLayoutInflater().inflate(R.layout.layout_attachment_actiontile, parent, false);
					TextView label = actionButton.findViewById(R.id.label);
					label.setText(R.string.part_camera);
					label.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.camera, 0, 0);
					actionButton.setOnClickListener(view -> requestTakePicture());
					return new ViewHolderImpl(actionButton);
				}
				case itemTypeOverflowButton: {
					View overflowButton = getLayoutInflater().inflate(R.layout.layout_attachment_overflow, parent, false);
					overflowButton.setOnClickListener(view -> requestGalleryFile());
					return new ViewHolderImpl(overflowButton);
				}
				case itemTypeContent: {
					return new MediaTileViewHolder(getLayoutInflater().inflate(R.layout.layout_attachment_mediatile, parent, false));
				}
				default:
					throw new IllegalArgumentException("Invalid view type received: " + viewType);
			}
		}
		
		private class MediaTileViewHolder extends RecyclerView.ViewHolder {
			//Creating the view values
			private ImageView imageThumbnail;
			private ImageView imageFlagGIF;
			
			MediaTileViewHolder(View itemView) {
				super(itemView);
				imageThumbnail = itemView.findViewById(R.id.image);
				imageFlagGIF = itemView.findViewById(R.id.image_flag_gif);
			}
		}
		
		private class ViewHolderImpl extends RecyclerView.ViewHolder {
			ViewHolderImpl(View itemView) {
				super(itemView);
			}
		}
		
		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			//Filtering out non-content items
			if(getItemViewType(position) != itemTypeContent) return;
			
			//Getting the file and returning if it is null
			File file = getItemAt(position);
			if(file == null) return;
			
			//Setting the image thumbnail
			Glide.with(Messaging.this)
					.load(file)
					.apply(RequestOptions.centerCropTransform())
					.transition(DrawableTransitionOptions.withCrossFade())
					.into(((MediaTileViewHolder) holder).imageThumbnail);
		}
		
		@Override
		public int getItemCount() {
			return fileList == null ? 2 : fileList.size() + 2;
		}
		
		@Override
		public int getItemViewType(int position) {
			if(position == 0) return itemTypeActionButton;
			else if(position + 1 == getItemCount()) return itemTypeOverflowButton;
			else return itemTypeContent;
		}
		
		private File getItemAt(int index) {
			if(index == 0 || index - 1 == fileList.size()) return null;
			return fileList.get(index - 1);
		}
		
		void setList(List<File> list) {
			fileList = list;
			notifyDataSetChanged();
		}
	}
	
	private static class TaskMarkMessageSendStyleViewed extends AsyncTask<ConversationManager.MessageInfo, Void, Void> {
		@Override
		protected Void doInBackground(ConversationManager.MessageInfo... items) {
			for(ConversationManager.MessageInfo item : items) DatabaseManager.getInstance().markSendStyleViewed(item.getLocalID());
			return null;
		}
	}
	
	static class LoadingViewHolder extends RecyclerView.ViewHolder {
		LoadingViewHolder(View itemView) {
			super(itemView);
		}
	}
	
	public static class SpeedyLinearLayoutManager extends LinearLayoutManager {
		public SpeedyLinearLayoutManager(Context context) {
			super(context);
		}
		
		public SpeedyLinearLayoutManager(Context context, int orientation, boolean reverseLayout) {
			super(context, orientation, reverseLayout);
		}
		
		public SpeedyLinearLayoutManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
			super(context, attrs, defStyleAttr, defStyleRes);
		}
		
		@Override
		public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
			//Calculating the speed
			float millisPerInch = 50 - Math.min(Math.abs(findFirstVisibleItemPosition() - position), Math.abs(findLastVisibleItemPosition() - position));
			if(millisPerInch < 0.01F) {
				scrollToPosition(position);
				return;
			}
			
			LinearSmoothScroller linearSmoothScroller = new LinearSmoothScroller(recyclerView.getContext()) {
				@Override
				public PointF computeScrollVectorForPosition(int targetPosition) {
					return SpeedyLinearLayoutManager.this.computeScrollVectorForPosition(targetPosition);
				}
				
				@Override
				protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
					return millisPerInch / displayMetrics.densityDpi;
				}
			};
			
			linearSmoothScroller.setTargetPosition(position);
			startSmoothScroll(linearSmoothScroller);
		}
	}
	
	private static class ActivityViewModel extends AndroidViewModel {
		//Creating the reference values
		static final byte inputStateText = 0;
		static final byte inputStateRecording = 1;
		
		static final byte messagesStateIdle = 0;
		static final byte messagesStateLoadingConversation = 1;
		static final byte messagesStateLoadingMessages = 2;
		static final byte messagesStateFailedConversation = 3;
		static final byte messagesStateFailedMessages = 4;
		static final byte messagesStateReady = 5;
		
		static final byte attachmentsStateIdle = 0;
		static final byte attachmentsStateLoading = 1;
		static final byte attachmentsStateLoaded = 2;
		static final byte attachmentsStateFailed = 3;
		
		private static final int attachmentsTileCount = 12;
		
		//Creating the state values
		byte inputState = inputStateText;
		MutableLiveData<Byte> messagesState = new MutableLiveData<>();
		
		byte attachmentsMediaState = attachmentsStateIdle;
		List<File> attachmentsMediaList = null;
		private WeakReference<AttachmentsLoadCallbacks> attachmentsMediaCallbacksReference = null;
		
		boolean isAttachmentsPanelOpen = false;
		boolean isDetailsPanelOpen = false;
		
		MutableLiveData<Boolean> progressiveLoadInProgress = new MutableLiveData<>();
		boolean progressiveLoadReachedLimit = false;
		int lastProgressiveLoadCount = -1;
		
		int lastUnreadCount = 0;
		
		//Creating the conversation values
		private long conversationID;
		private ConversationManager.ConversationInfo conversationInfo;
		private ArrayList<ConversationManager.ConversationItem> conversationItemList;
		private ArrayList<ConversationManager.MessageInfo> conversationGhostList;
		
		//Creating the attachment values
		File targetFile = null;
		
		Messaging.AudioMessageManager audioMessageManager = new AudioMessageManager();
		
		MutableLiveData<Integer> recordingDuration = new MutableLiveData<>();
		boolean recordingDiscardHover = false; //If the user is hovering the recording indicator over the discard zone
		MediaRecorder mediaRecorder = null;
		private final Handler recordingTimerHandler = new Handler(Looper.getMainLooper());
		private final Runnable recordingTimerHandlerRunnable = new Runnable() {
			@Override
			public void run() {
				//Adding a second
				recordingDuration.setValue(recordingDuration.getValue() + 1);
				
				//Scheduling the next run
				recordingTimerHandler.postDelayed(this, 1000);
			}
		};
		
		ActivityViewModel(Application application, long conversationID) {
			super(application);
			
			//Setting the values
			this.conversationID = conversationID;
			
			//Loading the data
			loadConversation();
		}
		
		@Override
		protected void onCleared() {
			//Cleaning up the media recorder
			if(inputState == inputStateRecording) stopRecording(true);
			if(mediaRecorder != null) mediaRecorder.release();
			
			//Releasing the audio player
			audioMessageManager.release();
			
			//Checking if the conversation is valid
			if(conversationInfo != null) {
				//Clearing the messages
				//conversationInfo.clearMessages();
				
				//Updating the conversation's unread message count
				conversationInfo.updateUnreadStatus(MainApplication.getInstance());
				new UpdateUnreadMessageCount(MainApplication.getInstance(), conversationID, conversationInfo.getUnreadMessageCount()).execute();
			}
		}
		
		/**
		 * Loads the conversation and its messages
		 */
		@SuppressLint("StaticFieldLeak")
		void loadConversation() {
			//Updating the state
			messagesState.setValue(messagesStateLoadingConversation);
			
			//Loading the conversation
			conversationInfo = ConversationManager.findConversationInfo(conversationID);
			if(conversationInfo != null) loadMessages();
			else new AsyncTask<Void, Void, ConversationManager.ConversationInfo>() {
				@Override
				protected ConversationManager.ConversationInfo doInBackground(Void... args) {
					return DatabaseManager.getInstance().fetchConversationInfo(getApplication(), conversationID);
				}
				
				@Override
				protected void onPostExecute(ConversationManager.ConversationInfo result) {
					//Setting the state to failed if the conversation info couldn't be fetched
					if(result == null) messagesState.setValue(messagesStateFailedConversation);
					else {
						conversationInfo = result;
						loadMessages();
					}
				}
			}.execute();
		}
		
		@SuppressLint("StaticFieldLeak")
		void loadMessages() {
			//Updating the state
			messagesState.setValue(messagesStateLoadingMessages);
			
			//Checking if the conversation already has lists
			ArrayList<ConversationManager.ConversationItem> existingConversationItems = conversationInfo.getConversationItems();
			ArrayList<ConversationManager.MessageInfo> existingGhostMessages = conversationInfo.getGhostMessages();
			if(existingConversationItems != null && existingGhostMessages != null) {
				//Setting the lists
				conversationItemList = existingConversationItems;
				conversationGhostList = existingGhostMessages;
				
				//Marking all messages as read (list will always be scrolled to the bottom)
				conversationInfo.setUnreadMessageCount(0);
				
				//Setting the state
				messagesState.setValue(messagesStateReady);
			} else {
				//Loading the messages
				new AsyncTask<Void, Void, List<ConversationManager.ConversationItem>>() {
					@Override
					protected List<ConversationManager.ConversationItem> doInBackground(Void... params) {
						//Loading the conversation items
						List<ConversationManager.ConversationItem> conversationItems = DatabaseManager.getInstance().loadConversationChunk(conversationInfo, false, 0);
						
						//Setting up the conversation item relations
						ConversationManager.setupConversationItemRelations(conversationItems, conversationInfo);
						
						//Returning the conversation items
						return conversationItems;
					}
					
					@Override
					protected void onPostExecute(List<ConversationManager.ConversationItem> messages) {
						//Checking if the messages are invalid
						if(messages == null) {
							//Setting the state
							messagesState.setValue(messagesStateFailedMessages);
							
							//Returning
							return;
						}
						
						//Creating the lists
						conversationItemList = new ArrayList<>();
						conversationGhostList = new ArrayList<>();
						
						//Recording the lists in the conversation info
						conversationInfo.setConversationLists(conversationItemList, conversationGhostList);
						
						//Replacing the conversation items
						conversationInfo.replaceConversationItems(MainApplication.getInstance(), messages);
						
						//Marking all messages as read (list will always be scrolled to the bottom)
						conversationInfo.setUnreadMessageCount(0);
						
						//Setting the state
						messagesState.setValue(messagesStateReady);
					}
				}.execute();
			}
		}
		
		@SuppressLint("StaticFieldLeak")
		void loadNextChunk() {
			//Returning if the conversation isn't ready, a load is already in progress or there are no conversation items
			if(messagesState.getValue() != messagesStateReady || isProgressiveLoadInProgress() || progressiveLoadReachedLimit || conversationInfo.getConversationItems().isEmpty()) return;
			
			//Setting the flags
			progressiveLoadInProgress.setValue(true);
			
			//Loading a chunk
			long lastMessageDate = conversationInfo.getConversationItems().get(0).getDate();
			new AsyncTask<Void, Void, List<ConversationManager.ConversationItem>>() {
				@Override
				protected List<ConversationManager.ConversationItem> doInBackground(Void... params) {
					//Loading the conversation items
					return DatabaseManager.getInstance().loadConversationChunk(conversationInfo, true, lastMessageDate);
				}
				
				@Override
				protected void onPostExecute(List<ConversationManager.ConversationItem> conversationItems) {
					//Setting the progressive load count
					lastProgressiveLoadCount = conversationItems.size();
					
					//Checking if there are no new conversation items
					if(conversationItems.isEmpty()) {
						//Disabling the progressive load (there are no more items to load)
						progressiveLoadReachedLimit = true;
					} else {
						//Loading the items
						List<ConversationManager.ConversationItem> allItems = conversationInfo.getConversationItems();
						if(allItems == null) return;
						
						//Adding the items
						conversationInfo.addChunk(conversationItems);
						
						//Updating the items' relations
						ConversationManager.addConversationItemRelations(conversationInfo, allItems, conversationItems, MainApplication.getInstance(), true);
					}
					
					//Finishing the progressive load
					progressiveLoadInProgress.setValue(false);
				}
			}.execute();
		}
		
		boolean isProgressiveLoadInProgress() {
			Boolean value = progressiveLoadInProgress.getValue();
			return value == null ? false : value;
		}
		
		/* int getRecordingDuration() {
			Integer value = recordingDuration.getValue();
			if(value == null) return 0;
			return value;
		} */
		
		void startRecordingTimer() {
			recordingDuration.setValue(0);
			recordingTimerHandler.postDelayed(recordingTimerHandlerRunnable, 1000);
		}
		
		void stopRecordingTimer() {
			recordingTimerHandler.removeCallbacks(recordingTimerHandlerRunnable);
		}
		
		private boolean startRecording(Activity activity) {
			//Setting up the media recorder
			boolean result = setupMediaRecorder(activity);
			
			//Returning false if the media recorder couldn't be set up
			if(!result) return false;
			
			//Finding a target file
			targetFile = MainApplication.findUploadFileTarget(activity, Constants.recordingName);
			
			try {
				//Creating the targets
				if(!targetFile.getParentFile().mkdir()) throw new IOException();
				//if(!targetFile.createNewFile()) throw new IOException();
			} catch(IOException exception) {
				//Printing the stack trace
				exception.printStackTrace();
				
				//Returning
				return false;
			}
			
			//Setting the media recorder file
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mediaRecorder.setOutputFile(targetFile);
			else mediaRecorder.setOutputFile(targetFile.getAbsolutePath());
			
			try {
				//Preparing the media recorder
				mediaRecorder.prepare();
			} catch(IOException exception) {
				//Printing the stack trace
				exception.printStackTrace();
				
				//Returning
				return false;
			}
			
			//Starting the recording timer
			startRecordingTimer();
			
			//Starting the media recorder
			mediaRecorder.start();
			
			//Resetting the flags
			recordingDiscardHover = false;
			
			//Returning true
			return true;
		}
		
		/**
		 * Stops the current recording session
		 * @param discard whether the file should be discarded or not
		 * @return the file's availability (to be able to use or send)
		 */
		private boolean stopRecording(boolean discard) {
			//Returning false if the input state is not recording
			if(inputState != inputStateRecording) return true;
			
			//Stopping the timer
			stopRecordingTimer();
			
			try {
				//Stopping the media recorder
				mediaRecorder.stop();
			} catch(RuntimeException stopException) { //The media recorder couldn't capture any media
				//Showing a toast
				Toast.makeText(MainApplication.getInstance(), R.string.imperative_recording_instructions, Toast.LENGTH_LONG).show();
				
				//Returning false
				return false;
			}
			
			//Checking if the recording was under a second
			if(recordingDuration.getValue() < 1) {
				//Showing a toast
				Toast.makeText(MainApplication.getInstance(), R.string.imperative_recording_instructions, Toast.LENGTH_LONG).show();
				
				//Discarding the file
				discard = true;
			}
			
			//Checking if the recording should be discarded
			if(discard) {
				//Deleting the file
				targetFile.delete();
				
				//Returning false
				return false;
			}
			
			//Returning true
			return true;
		}
		
		private boolean setupMediaRecorder(Activity activity) {
			//Returning false if the required permissions have not been granted
			if(Constants.requestPermission(activity, new String[]{Manifest.permission.RECORD_AUDIO}, Constants.permissionRecordAudio)) {
				//Notifying the user via a toast
				Toast.makeText(activity, R.string.message_permissiondetails_microphone_missing, Toast.LENGTH_SHORT).show();
				
				//Returning false
				return false;
			}
			
			//Setting the media recorder
			if(mediaRecorder == null) mediaRecorder = new MediaRecorder();
			else mediaRecorder.reset();
			
			//Configuring the media recorder
			mediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
			mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
			mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
			mediaRecorder.setMaxDuration(10 * 60 * 1000); //10 minutes
			
			//Returning true
			return true;
		}
		
		@SuppressLint("StaticFieldLeak")
		void indexAttachmentsMedia(AttachmentsLoadCallbacks listener) {
			//Updating the listener
			attachmentsMediaCallbacksReference = new WeakReference<>(listener);
			
			//Returning if the state is incapable of handling the request
			if(attachmentsMediaState == attachmentsStateLoading || attachmentsMediaState == attachmentsStateLoaded) return;
			
			//Setting the state
			attachmentsMediaState = attachmentsStateLoading;
			
			//Starting the asynchronous task
			new AsyncTask<Void, Void, List<File>>() {
				@Override
				protected List<File> doInBackground(Void... params) {
					try {
						//Creating the list
						List<File> list = new ArrayList<>();
						
						//Querying the media files
						try(Cursor cursor = getApplication().getContentResolver().query(
								MediaStore.Files.getContentUri("external"),
								new String[]{MediaStore.MediaColumns.DATA},
								MediaStore.Files.FileColumns.MEDIA_TYPE + " = " + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = " + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
								null,
								MediaStore.Files.FileColumns.DATE_ADDED + " DESC" + ' ' + "LIMIT " + attachmentsTileCount)) {
							int indexData = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
							while(cursor.moveToNext()) list.add(new File(cursor.getString(indexData)));
						}
						
						//Returning the list
						return list;
					} catch(SQLiteException exception) {
						//Logging the exception
						exception.printStackTrace();
						Crashlytics.logException(exception);
						
						//Returning null
						return null;
					}
				}
				
				@Override
				protected void onPostExecute(List<File> files) {
					//Checking if the data is invalid
					if(files == null) {
						//Setting the state
						attachmentsMediaState = attachmentsStateFailed;
						
						//Telling the listener
						AttachmentsLoadCallbacks listener = attachmentsMediaCallbacksReference.get();
						if(listener != null) listener.onLoadFinished(false);
					} else {
						//Setting the state
						attachmentsMediaState = attachmentsStateLoaded;
						
						//Setting the items
						attachmentsMediaList = files;
						
						//Telling the listener
						AttachmentsLoadCallbacks listener = attachmentsMediaCallbacksReference.get();
						if(listener != null) listener.onLoadFinished(true);
					}
				}
			}.execute();
		}
		
		interface AttachmentsLoadCallbacks {
			void onLoadFinished(boolean successful);
		}
	}
	
	static class AudioMessageManager {
		//Creating the values
		private MediaPlayer mediaPlayer = new MediaPlayer();
		private String currentFilePath = "";
		private WeakReference<ConversationManager.AudioAttachmentInfo> attachmentReference;
		private Handler mediaPlayerHandler = new Handler(Looper.getMainLooper());
		private Runnable mediaPlayerHandlerRunnable = new Runnable() {
			@Override
			public void run() {
				//Updating the attachment
				ConversationManager.AudioAttachmentInfo attachment = getAttachmentInfo();
				if(attachment != null) attachment.setMediaProgress(mediaPlayer.getCurrentPosition());
				
				//Running again
				mediaPlayerHandler.postDelayed(this, 10);
			}
		};
		
		AudioMessageManager() {
			//Setting the media player listeners
			mediaPlayer.setOnPreparedListener(player -> {
				//Playing the media
				player.start();
				
				//Starting the timer
				mediaPlayerHandlerRunnable.run();
				
				//Updating the attachment
				ConversationManager.AudioAttachmentInfo attachment = getAttachmentInfo();
				if(attachment != null) attachment.setMediaPlaying(true);
			});
			mediaPlayer.setOnCompletionListener(player -> {
				//Cancelling the timer
				mediaPlayerHandler.removeCallbacks(mediaPlayerHandlerRunnable);
				
				//Updating the attachment
				ConversationManager.AudioAttachmentInfo attachment = getAttachmentInfo();
				if(attachment != null) attachment.resetPlaying();
			});
		}
		
		private ConversationManager.AudioAttachmentInfo getAttachmentInfo() {
			if(attachmentReference == null) return null;
			return attachmentReference.get();
		}
		
		void release() {
			//Cancelling the timer
			if(mediaPlayer.isPlaying()) mediaPlayerHandler.removeCallbacks(mediaPlayerHandlerRunnable);
			
			//Releasing the media player
			mediaPlayer.release();
		}
		
		void prepareMediaPlayer(long messageID, File file, ConversationManager.AudioAttachmentInfo attachmentInfo) {
			//Returning if the attachment is already playing
			if(currentFilePath.equals(file.getPath())) return;
			
			//Cancelling the timer
			if(mediaPlayer.isPlaying()) mediaPlayerHandler.removeCallbacks(mediaPlayerHandlerRunnable);
			
			//Resetting the media player
			mediaPlayer.reset();
			
			//Resetting the old view
			{
				ConversationManager.AudioAttachmentInfo oldAttachmentInfo = getAttachmentInfo();
				if(oldAttachmentInfo != null) oldAttachmentInfo.resetPlaying();
			}
			
			try {
				//Creating the media player
				mediaPlayer.setDataSource(file.getPath());
			} catch(IOException exception) {
				//Printing the stack trace
				exception.printStackTrace();
				
				//Returning
				return;
			}
			
			//Setting the attachment reference
			attachmentReference = new WeakReference<>(attachmentInfo);
			
			//Setting the values
			currentFilePath = file.getPath();
			
			//Preparing the media player
			mediaPlayer.prepareAsync();
		}
		
		boolean isCurrentMessage(File file) {
			return currentFilePath.equals(file.getPath());
		}
		
		void togglePlaying() {
			//Checking if the media player is playing
			if(mediaPlayer.isPlaying()) {
				//Pausing the media player
				mediaPlayer.pause();
				
				//Cancelling the playback timer
				mediaPlayerHandler.removeCallbacks(mediaPlayerHandlerRunnable);
			} else {
				//Playing the media player
				mediaPlayer.start();
				
				//Starting the playback timer
				mediaPlayerHandlerRunnable.run();
			}
			
			//Telling the attachment
			ConversationManager.AudioAttachmentInfo attachment = getAttachmentInfo();
			if(attachment != null) attachment.setMediaPlaying(mediaPlayer.isPlaying());
		}
	}
	
	private static class UpdateUnreadMessageCount extends AsyncTask<Void, Void, Void> {
		private final WeakReference<Context> contextReference;
		private final long conversationID;
		private final int count;
		
		UpdateUnreadMessageCount(Context context, long conversationID, int count) {
			contextReference = new WeakReference<>(context);
			
			this.conversationID = conversationID;
			this.count = count;
		}
		
		@Override
		protected Void doInBackground(Void... voids) {
			//Getting the context
			Context context = contextReference.get();
			if(context == null) return null;
			
			//Updating the time
			DatabaseManager.getInstance().setUnreadMessageCount(conversationID, count);
			
			//Returning
			return null;
		}
	}
	
	private static class AddGhostMessageTask extends AsyncTask<Void, Void, Void> {
		private final WeakReference<Context> contextReference;
		private final ConversationManager.MessageInfo messageInfo;
		private final Runnable onFinishListener;
		
		AddGhostMessageTask(Context context, ConversationManager.MessageInfo messageInfo, Runnable onFinishListener) {
			//Setting the references
			contextReference = new WeakReference<>(context);
			
			//Setting the other values
			this.messageInfo = messageInfo;
			this.onFinishListener = onFinishListener;
		}
		
		@Override
		protected Void doInBackground(Void... parameters) {
			//Getting the context
			Context context = contextReference.get();
			if(context == null) return null;
			
			//Adding the item to the database
			DatabaseManager.getInstance().addConversationItem(messageInfo);
			
			//Returning
			return null;
		}
		
		@Override
		protected void onPostExecute(Void aVoid) {
			//Calling the finish listener
			onFinishListener.run();
		}
	}
	
	private static class ActivityCallbacks extends ConversationManager.ConversationInfo.ActivityCallbacks {
		//Creating the references
		private final WeakReference<Messaging> activityReference;
		
		ActivityCallbacks(Messaging activity) {
			//Setting the references
			activityReference = new WeakReference<>(activity);
		}
		
		@Override
		public void listUpdateFully() {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Updating the adapter
			activity.messageList.getRecycledViewPool().clear();
			activity.messageListAdapter.notifyDataSetChanged();
			//activity.messageList.scheduleLayoutAnimation();
		}
		
		@Override
		void listUpdateInserted(int index) {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Updating the adapter
			activity.messageListAdapter.notifyItemInserted(index);
		}
		
		@Override
		void listUpdateRemoved(int index) {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Updating the adapter
			activity.messageListAdapter.notifyItemRemoved(index);
		}
		
		@Override
		public void listUpdateMove(int from, int to) {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Updating the adapter
			activity.messageListAdapter.notifyItemChanged(from);
			activity.messageListAdapter.notifyItemMoved(from, to);
		}
		
		@Override
		void listUpdateUnread() {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Notifying the scroll listener
			activity.messageListScrollListener.onScrolled(activity.messageList, 0, 0);
		}
		
		@Override
		void listScrollToBottom() {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Updating the adapter
			activity.messageListAdapter.scrollToBottom();
		}
		
		@Override
		void listAttemptScrollToBottom(int... newIndices) {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Updating the adapter
			boolean newMessageAdded = false;
			for(int index : newIndices) {
				if(activity.messageListAdapter.isDirectlyBelowFrame(index)) {
					newMessageAdded = true;
					break;
				}
			}
			
			//Scrolling to the bottom of the list
			if(newMessageAdded) activity.messageListAdapter.scrollToBottom();
		}
		
		@Override
		void chatUpdateTitle() {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Building the conversation title
			activity.viewModel.conversationInfo.buildTitle(activity, (result, wasTasked) -> {
				//Setting the title in the app bar
				activity.getSupportActionBar().setTitle(result);
				
				//Updating the task description
				activity.lastTaskDescription = new ActivityManager.TaskDescription(result, activity.lastTaskDescription.getIcon(), activity.viewModel.conversationInfo.getConversationColor());
				activity.setTaskDescription(activity.lastTaskDescription);
			});
		}
		
		@Override
		void chatUpdateUnreadCount() {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Updating the unread indicator
			activity.updateUnreadIndicator();
		}
		
		@Override
		void chatUpdateMemberAdded(ConversationManager.MemberInfo member, int index) {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Adding the member
			activity.addMemberView(member, index, true);
		}
		
		@Override
		void chatUpdateMemberRemoved(ConversationManager.MemberInfo member, int index) {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return;
			
			//Removing the member
			activity.removeMemberView(member);
		}
		
		@Override
		AudioMessageManager getAudioMessageManager() {
			//Getting the activity
			Messaging activity = activityReference.get();
			if(activity == null) return null;
			
			//Returning the view model's audio message manager
			return activity.viewModel.audioMessageManager;
		}
		
		@Override
		void playScreenEffect(String screenEffect, View target) {
			Messaging activity = activityReference.get();
			if(activity != null) activity.playScreenEffect(screenEffect, target);
		}
	}
	
	void detailSwitchConversationColor(int newColor) {
		//Updating the conversation color
		viewModel.conversationInfo.setConversationColor(newColor);
		
		//Coloring the UI
		colorUI(findViewById(android.R.id.content));
		
		//Updating the conversation color on disk
		DatabaseManager.getInstance().updateConversationColor(viewModel.conversationInfo.getLocalID(), newColor);
		
		//Updating the member if there is only one member as well
		if(viewModel.conversationInfo.getConversationMembers().size() == 1) detailSwitchMemberColor(viewModel.conversationInfo.getConversationMembers().get(0), newColor);
	}
	
	void detailSwitchMemberColor(ConversationManager.MemberInfo member, int newColor) {
		//Updating the user's color
		member.setColor(newColor);
		
		//Updating the view
		View memberView = memberListViews.get(member);
		((ImageView) memberView.findViewById(R.id.button_change_color)).setColorFilter(member.getColor(), android.graphics.PorterDuff.Mode.MULTIPLY);
		((ImageView) memberView.findViewById(R.id.profile_default)).setColorFilter(member.getColor(), android.graphics.PorterDuff.Mode.MULTIPLY);
		
		//Updating the message colors
		if(viewModel.conversationItemList != null) for(ConversationManager.ConversationItem conversationItem : viewModel.conversationItemList) conversationItem.updateViewColor(this);
		
		//Updating the member color on disk
		DatabaseManager.getInstance().updateMemberColor(viewModel.conversationInfo.getLocalID(), member.getName(), newColor);
	}
	
	private static final String colorDialogTag = "colorPickerDialog";
	void showColorDialog(ConversationManager.MemberInfo member, int currentColor) {
		//Starting a fragment transaction
		FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
		
		//Removing the previous fragment if it already exists
		Fragment previousFragment = getSupportFragmentManager().findFragmentByTag(colorDialogTag);
		if(previousFragment != null) fragmentTransaction.remove(previousFragment);
		fragmentTransaction.addToBackStack(null);
		
		//Creating and showing the dialog fragment
		ColorPickerDialog newFragment = ColorPickerDialog.newInstance(member, currentColor);
		newFragment.show(fragmentTransaction, colorDialogTag);
		currentColorPickerDialog = newFragment;
		currentColorPickerDialogMember = member;
	}
	
	public static class ColorPickerDialog extends DialogFragment {
		//Creating the instantiation values
		private ConversationManager.MemberInfo member = null;
		private int selectedColor;
		
		@Override
		public void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			
			//Getting the arguments
			if(getArguments().containsKey(Constants.intentParamData))
				member = (ConversationManager.MemberInfo) getArguments().getSerializable(Constants.intentParamData);
			selectedColor = getArguments().getInt(Constants.intentParamCurrent);
		}
		
		@Override
		public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
			//Configuring the dialog
			getDialog().setTitle(member == null ? R.string.action_editconversationcolor : R.string.action_editcontactcolor);
			
			//Inflating the view
			View dialogView = inflater.inflate(R.layout.dialog_colorpicker, container, false);
			ViewGroup contentViewGroup = dialogView.findViewById(R.id.colorpicker_itemview);
			
			int padding = Constants.dpToPx(24);
			contentViewGroup.setPadding(padding, padding, padding, padding);
			
			//Adding the elements
			for(int i = 0; i < ConversationManager.ConversationInfo.standardUserColors.length; i++) {
				//Getting the color
				final int standardColor = ConversationManager.ConversationInfo.standardUserColors[i];
				
				//Inflating the layout
				View item = inflater.inflate(R.layout.dialog_colorpicker_item, contentViewGroup, false);
				
				//Configuring the layout
				ImageView colorView = item.findViewById(R.id.colorpickeritem_color);
				colorView.setColorFilter(standardColor);
				
				final boolean isSelectedColor = selectedColor == standardColor;
				if(isSelectedColor) item.findViewById(R.id.colorpickeritem_selection).setVisibility(View.VISIBLE);
				
				//Setting the click listener
				colorView.setOnClickListener(view -> {
					//Telling the activity
					if(!isSelectedColor) {
						if(member == null) ((Messaging) getActivity()).detailSwitchConversationColor(standardColor);
						else ((Messaging) getActivity()).detailSwitchMemberColor(member, standardColor);
					}
					
					getDialog().dismiss();
				});
				
				//Adding the view to the layout
				contentViewGroup.addView(item);
			}
			
			//Returning the view
			return dialogView;
		}
		
		static ColorPickerDialog newInstance(ConversationManager.MemberInfo memberInfo, int selectedColor) {
			//Creating the instance
			ColorPickerDialog instance = new ColorPickerDialog();
			
			//Adding the member info
			Bundle bundle = new Bundle();
			bundle.putSerializable(Constants.intentParamData, memberInfo);
			bundle.putSerializable(Constants.intentParamCurrent, selectedColor);
			instance.setArguments(bundle);
			
			//Returning the instance
			return instance;
		}
	}
}