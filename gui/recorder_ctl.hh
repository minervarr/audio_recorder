#pragma once

// Entry points the Java service calls from the UI thread. The work happens
// here, not in the view, because the activity (and its android_main) can
// already have returned while a recording is still in the foreground service.
namespace rec_ui {

void onExternalStop();
void onUsbSignal();

}  // namespace rec_ui
