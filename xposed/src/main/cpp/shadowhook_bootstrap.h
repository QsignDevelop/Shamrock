#pragma once

// Ensures ByteDance ShadowHook is initialized once per process (SHARED mode).
// Safe to call from sign / anti-detect / hide init paths.
bool shamrock_ensure_shadowhook_init();
