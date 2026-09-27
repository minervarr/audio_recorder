#pragma once

#include <memory>

#include "app_view.hh"

class Host;

class RecorderView : public AppView {
public:
    RecorderView() = default;
    ~RecorderView() override;

    bool create(std::unique_ptr<Host> host);
    void run();

    void onHostResized() override;
    void onHostLayoutInvalidated() override;
    void onHostExposed() override;
    void shutdown() override;
    void onHostReady() override;
    void onHostFocusGained() override;
    void onSurfaceLost() override;
    bool onSurfaceRecreated() override;
    void onAppEvent(int id, intptr_t p1, intptr_t p2) override;
    void onLButtonUp(int x, int y) override;
    void onPointerDown(int pointerId, int x, int y) override;
    void onPointerMove(int pointerId, int x, int y) override;
    void onPointerUp(int pointerId, int x, int y) override;
    void onMouseWheel(int x, int y, int delta) override;
    void onDragEnd(int dx, int dy) override;
    void onTimer(int timerId) override;

private:
    struct Impl;
    Impl* self_ = nullptr;
    std::unique_ptr<Host> host_;
    bool running_ = true;
};
