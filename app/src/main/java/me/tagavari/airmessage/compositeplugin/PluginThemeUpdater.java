package me.tagavari.airmessage.compositeplugin;

import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.Nullable;

import me.tagavari.airmessage.activity.Preferences;
import me.tagavari.airmessage.composite.AppCompatActivityPlugin;

public class PluginThemeUpdater extends AppCompatActivityPlugin {
	private int currentNightMode;
	private boolean currentAMOLEDState;
	
	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		//Recording the state
		currentNightMode = getCurrentNightMode();
		currentAMOLEDState = Preferences.getPreferenceAMOLED(getActivity());
	}
	
	@Override
	public void onResume() {
		super.onResume();
		
		//Recreating the activity if the theme has changed
		if(currentNightMode != getCurrentNightMode() || currentAMOLEDState != Preferences.getPreferenceAMOLED(getActivity())) {
			getActivity().recreate();
			/* if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				((AlarmManager) getActivity().getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.RTC, System.currentTimeMillis() + 100, PendingIntent.getActivity(getActivity(), 123456, getActivity().getIntent(), PendingIntent.FLAG_CANCEL_CURRENT));
				System.exit(0);
			} else getActivity().recreate(); */
		}
	}
	
	private int getCurrentNightMode() {
		return getActivity().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
	}
}