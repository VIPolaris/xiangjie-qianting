# Media3 and DataStore publish consumer rules. Keep only the service entry points
# that Android creates by class name.
-keep class com.polaris.caraudiotrigger.device.CarCompanionService { *; }
-keep class com.polaris.caraudiotrigger.device.BluetoothConnectionReceiver { *; }
-keep class com.polaris.caraudiotrigger.device.RestoreObservationReceiver { *; }
-keep class com.polaris.caraudiotrigger.playback.PlaybackService { *; }

