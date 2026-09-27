#include "recorder_view.hh"
#include "plat.hh"

#include "os/android_host.hh"

#include <android_native_app_glue.h>

#include <memory>

extern "C" void android_main(android_app* state) {
    plat::setApp(state);
    auto host = std::make_unique<AndroidHost>(state, nullptr, nullptr,
                                              /*requestAllFilesAccess=*/false);
    RecorderView app;
    if (app.create(std::move(host))) app.run();
    plat::setHost(nullptr);
}
