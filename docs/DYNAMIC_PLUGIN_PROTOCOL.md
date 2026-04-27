# Dynamic plugin protocol — design

Goal: new plugins are **drop-in glass APKs**. The phone app discovers them, renders their settings UI, and routes their config without needing a rebuild.

## Plugin manifest (glass side, added to each plugin APK)

```xml
<service android:name=".XyzGlassPluginService" ...>
    <intent-filter>
        <action android:name="com.glasshole.plugin.GLASS_PLUGIN"/>
    </intent-filter>
    <meta-data android:name="com.glasshole.plugin.ID"          android:value="notes"/>
    <meta-data android:name="com.glasshole.plugin.NAME"        android:value="Notes"/>
    <meta-data android:name="com.glasshole.plugin.DESCRIPTION" android:value="Quick text notes on glass"/>
    <meta-data android:name="com.glasshole.plugin.SCHEMA"      android:resource="@raw/plugin_schema"/>
</service>
```

Only `ID` is required today. The other three are new.

## Schema file (`res/raw/plugin_schema.json` on each plugin)

```json
{
  "version": 1,
  "settings": [
    { "key": "platform", "type": "radio", "label": "Platform",
      "default": "twitch",
      "options": [
        { "value": "twitch",  "label": "Twitch"  },
        { "value": "youtube", "label": "YouTube" }
      ]
    },
    { "key": "twitch_channel",   "type": "text",     "label": "Twitch channel",
      "visible_when": { "key": "platform", "eq": "twitch" } },
    { "key": "youtube_api_key",  "type": "password", "label": "YouTube API key",
      "visible_when": { "key": "platform", "eq": "youtube" } },
    { "key": "font_size",        "type": "slider",   "label": "Font size",
      "min": 10, "max": 24, "step": 1, "default": 14 },
    { "key": "audio",            "type": "checkbox", "label": "Audio enabled",
      "default": true }
  ],
  "workers": [
    { "primitive": "chat-twitch",
      "enabled_when": { "key": "platform", "eq": "twitch" },
      "params": { "channel": "${twitch_channel}" }
    }
  ]
}
```

### Field types (initial set)

| type       | UI                                             | JSON value         |
|------------|------------------------------------------------|--------------------|
| `text`     | single-line EditText                           | string             |
| `password` | EditText with `inputType="textPassword"`       | string             |
| `number`   | EditText with `inputType="number"` (free-form) | number             |
| `slider`   | SeekBar + value label                          | number             |
| `radio`    | radio button group from `options`              | string (the value) |
| `checkbox` | Switch                                         | boolean            |
| `section`  | non-editable header (grouping)                 | —                  |

Optional per-field keys:
- `default` — seeded if no current value
- `visible_when` / `enabled_when` — simple `{"key": X, "eq": V}` conditional on another setting

### Workers (for phase 2 — present in the schema from day one)

Each worker names a **primitive** (implemented in the phone app) and passes parameters. `${setting_key}` in a param value substitutes the plugin's current config. Phase 1 parses this but doesn't wire the worker registry yet — that's phase 2.

## Wire protocol (over the existing BT `PLUGIN:<id>:<type>:<payload>` envelope)

| Direction | Type | Payload | Meaning |
|-----------|------|---------|---------|
| glass → phone | `PLUGIN_LIST` (top-level, not PLUGIN:) | JSON array of `{id, name, description, version}` | Sent on connect |
| phone → glass (via plugin) | `SCHEMA_REQ` | "" | Asks a specific plugin for its schema |
| glass → phone | `SCHEMA_RESP` | raw schema JSON | Plugin's response |
| phone → glass (via plugin) | `CONFIG_READ` | "" | Asks a plugin for its current saved config |
| glass → phone | `CONFIG` | JSON `{key: value}` of all settings | Plugin's response |
| phone → glass (via plugin) | `CONFIG_WRITE` | JSON `{key: value}` of fields to update | Phone commits edits |

The `PLUGIN:` envelope means existing infrastructure routes these to each plugin's `onMessageFromPhone` for free. Plugins just need to handle three new types (`SCHEMA_REQ`, `CONFIG_READ`, `CONFIG_WRITE`).

`PLUGIN_LIST` is distinct because it's base-app-owned (glass enumerates packages, phone doesn't ask a specific plugin for it).

## Config authority

- **Glass plugin's `SharedPreferences` is the source of truth.**
- Phone caches the last-known config per plugin in memory only (for fast form repaint); reloads via `CONFIG_READ` when settings screen opens.
- On `CONFIG_WRITE`, plugin persists immediately and optionally emits any side-effects (e.g., restarting its worker).

## Phone-side architecture

- **`PluginDirectory`** (singleton) holds `Map<id, PluginInfo>` (name, desc, version) + lazy-loaded `schemas: Map<id, Schema>` + latest `configs: Map<id, JSONObject>`.
- **`PluginSettingsActivity`** takes a plugin ID extra, pulls schema from Directory (or fetches if missing), inflates form, loads config, saves edits.
- **`PluginsActivity`** becomes a list view over `PluginDirectory.all()`. No hardcoded map.

## Glass-side architecture

- `PluginHostService` (EE2) / equivalent in EE1/XE base app enumerates `queryIntentServices(ACTION_GLASS_PLUGIN, GET_META_DATA)` on connect, builds `PluginInfo` list, emits `PLUGIN_LIST`.
- `SCHEMA_REQ` / `CONFIG_READ` / `CONFIG_WRITE` routing reuses the existing `PLUGIN:<id>:<type>:<payload>` dispatch — no plumbing changes, just new message types the plugins recognize.

## Migration

Existing hardcoded `ChatPlugin`, `BroadcastPlugin`, etc. on the phone side get deleted as their glass-side counterparts gain `SCHEMA` + config handlers. Workers (`TwitchChatClient`, `FusedLocationProviderClient` calls) move to the Phase 2 primitive registry.
