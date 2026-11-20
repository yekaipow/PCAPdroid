/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid. If not, see <http://www.gnu.org/licenses/>.
 */

package com.emanuelef.remote_capture.mcp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.BuildConfig;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.CaptureStats;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;

/* Implements the MCP tools exposed by the PCAPdroid MCP server.
 *
 * Tools:
 *  - get_capture_status:   capture running state, version and statistics
 *  - start_capture:        start a capture using the saved app settings
 *  - stop_capture:         stop the running capture and return final stats
 *  - list_connections:     list the captured connections (with limit/offset)
 *  - get_connection_details: full details and payload of a single connection
 *
 * All the access to the running capture is performed via the existing
 * CaptureService static API, so no capture logic is duplicated here.
 */
public class McpTools {
    private static final String TAG = "McpTools";
    private static final int MAX_PAYLOAD_CHUNKS = 32;
    private static final int MAX_CHUNK_BYTES = 4096;

    private final Context mContext;
    private final AppsResolver mAppsResolver;

    public McpTools(Context context) {
        mContext = context.getApplicationContext();
        mAppsResolver = new AppsResolver(mContext);
    }

    /* Returns the JSON Schemas of the exposed tools (for tools/list). */
    public JsonArray getToolDefinitions() {
        JsonArray tools = new JsonArray();

        tools.add(tool("get_capture_status",
                "Get the current capture status, the PCAPdroid version and traffic statistics.",
                obj()));

        JsonObject startSchema = obj();
        startSchema.add("properties", props(
                property("app_filter", "string", "Comma-separated package names to capture (overrides the saved filter)."),
                property("root_capture", "boolean", "Use root capture instead of VPN mode.")
        ));
        tools.add(tool("start_capture",
                "Start a packet capture using the settings saved in PCAPdroid. VPN permission must have been granted before.",
                startSchema));

        tools.add(tool("stop_capture",
                "Stop the running capture and return the final traffic statistics.",
                obj()));

        JsonObject listSchema = obj();
        listSchema.add("properties", props(
                property("limit", "integer", "Maximum number of connections to return (default 50, max 1000)."),
                property("offset", "integer", "Index of the first connection to return (default 0).")
        ));
        tools.add(tool("list_connections",
                "List the connections captured so far, from the most recent to the oldest.",
                listSchema));

        JsonObject detailsSchema = obj();
        detailsSchema.add("properties", props(
                property("id", "integer", "The connection id (incr_id) as returned by list_connections."),
                property("include_payload", "boolean", "Include the connection payload chunks as text (default false).")
        ));
        String[] required = {"id"};
        detailsSchema.add("required", stringArray(required));
        tools.add(tool("get_connection_details",
                "Get full details of a connection, optionally including its payload chunks.",
                detailsSchema));

        return tools;
    }

    /* Executes a tool call. Returns the MCP "result" object (content array). */
    public JsonObject callTool(String name, JsonElement arguments) {
        try {
            JsonObject args = (arguments != null && arguments.isJsonObject())
                    ? arguments.getAsJsonObject() : obj();

            switch(name) {
                case "get_capture_status":
                    return textResult(statusJson().toString());
                case "start_capture":
                    return startCapture(args);
                case "stop_capture":
                    return stopCapture();
                case "list_connections":
                    return textResult(listConnections(args).toString());
                case "get_connection_details":
                    return getConnectionDetails(args);
                default:
                    return errorResult("Unknown tool: " + name);
            }
        } catch(Exception e) {
            Log.e(TAG, "Tool " + name + " failed: " + e);
            return errorResult("Tool execution failed: " + e.getLocalizedMessage());
        }
    }

    private JsonObject statusJson() {
        JsonObject res = obj();
        res.addProperty("running", CaptureService.isServiceActive());
        res.addProperty("version_name", BuildConfig.VERSION_NAME);
        res.addProperty("version_code", BuildConfig.VERSION_CODE);
        res.addProperty("has_error", CaptureService.hasError());

        ConnectionsRegister reg = CaptureService.getConnsRegister();
        res.addProperty("connections", (reg != null) ? reg.getConnCount() : 0);

        CaptureStats stats = CaptureService.getStats();
        JsonObject s = obj();
        s.addProperty("bytes_sent", stats.bytes_sent);
        s.addProperty("bytes_rcvd", stats.bytes_rcvd);
        s.addProperty("pkts_sent", stats.pkts_sent);
        s.addProperty("pkts_rcvd", stats.pkts_rcvd);
        s.addProperty("pkts_dropped", stats.pkts_dropped);
        s.addProperty("active_conns", stats.active_conns);
        s.addProperty("tot_conns", stats.tot_conns);
        s.addProperty("pcap_dump_size", stats.pcap_dump_size);
        res.add("stats", s);
        return res;
    }

    private JsonObject startCapture(JsonObject args) {
        if(CaptureService.isServiceActive())
            return errorResult("A capture is already running.");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        CaptureSettings settings = new CaptureSettings(mContext, prefs);

        if(args.has("root_capture") && args.get("root_capture").isJsonPrimitive())
            settings.root_capture = args.get("root_capture").getAsBoolean();

        if(args.has("app_filter") && args.get("app_filter").isJsonPrimitive()) {
            String filter = args.get("app_filter").getAsString();
            if(!filter.isEmpty())
                settings.app_filter = new HashSet<>(Arrays.asList(filter.split(",")));
        }

        if(!settings.root_capture && !settings.readFromPcap()) {
            Intent prepare = VpnService.prepare(mContext);
            if(prepare != null)
                return errorResult("VPN permission has not been granted. Open PCAPdroid and start a capture once from the UI to grant it.");
        }

        Intent intent = new Intent(mContext, CaptureService.class);
        intent.putExtra("settings", settings);
        ContextCompat.startForegroundService(mContext, intent);

        JsonObject res = obj();
        res.addProperty("running", true);
        res.addProperty("message", "Capture start requested.");
        return textResult(res.toString());
    }

    private JsonObject stopCapture() {
        if(!CaptureService.isServiceActive())
            return errorResult("No capture is running.");

        CaptureService.stopService();
        CaptureService.waitForCaptureStop();

        JsonObject res = statusJson();
        res.addProperty("message", "Capture stopped.");
        return textResult(res.toString());
    }

    private JsonArray listConnections(JsonObject args) {
        JsonArray result = new JsonArray();
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if(reg == null)
            return result;

        int limit = 50, offset = 0;
        if(args.has("limit") && args.get("limit").isJsonPrimitive())
            limit = Math.max(0, Math.min(1000, args.get("limit").getAsInt()));
        if(args.has("offset") && args.get("offset").isJsonPrimitive())
            offset = Math.max(0, args.get("offset").getAsInt());

        int count = reg.getConnCount();
        int end = Math.min(count, offset + limit);
        for(int i = offset; i < end; i++) {
            ConnectionDescriptor conn = reg.getConn(i);
            if(conn != null)
                result.add(connectionJson(conn, false));
        }
        return result;
    }

    private JsonObject getConnectionDetails(JsonObject args) {
        if(!args.has("id") || !args.get("id").isJsonPrimitive())
            return errorResult("Missing required parameter: id");

        int id;
        try {
            id = args.get("id").getAsInt();
        } catch(Exception e) {
            return errorResult("Invalid connection id");
        }

        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if(reg == null)
            return errorResult("No capture data available.");

        ConnectionDescriptor conn = reg.getConnById(id);
        if(conn == null)
            return errorResult("Connection not found: " + id);

        boolean includePayload = false;
        if(args.has("include_payload") && args.get("include_payload").isJsonPrimitive())
            includePayload = args.get("include_payload").getAsBoolean();

        return textResult(connectionJson(conn, includePayload).toString());
    }

    private JsonObject connectionJson(ConnectionDescriptor conn, boolean includePayload) {
        JsonObject o = obj();
        o.addProperty("id", conn.incr_id);
        o.addProperty("ipver", conn.ipver);
        o.addProperty("ipproto", conn.ipproto);
        o.addProperty("src_ip", conn.src_ip);
        o.addProperty("src_port", conn.src_port);
        o.addProperty("dst_ip", conn.dst_ip);
        o.addProperty("dst_port", conn.dst_port);
        o.addProperty("l7proto", nullToEmpty(conn.l7proto));
        o.addProperty("info", nullToEmpty(conn.info));
        o.addProperty("url", nullToEmpty(conn.url));
        o.addProperty("uid", conn.uid);
        o.addProperty("status", conn.getStatus().name());
        o.addProperty("decryption_status", conn.getDecryptionStatus().name());
        o.addProperty("sent_bytes", conn.sent_bytes);
        o.addProperty("rcvd_bytes", conn.rcvd_bytes);
        o.addProperty("sent_pkts", conn.sent_pkts);
        o.addProperty("rcvd_pkts", conn.rcvd_pkts);
        o.addProperty("country", nullToEmpty(conn.country));
        o.addProperty("first_seen", conn.first_seen);
        o.addProperty("last_seen", conn.last_seen);
        o.addProperty("is_blocked", conn.is_blocked);
        o.addProperty("is_blacklisted", conn.isBlacklisted());
        o.addProperty("is_decrypted", conn.isDecrypted());
        com.emanuelef.remote_capture.model.AppDescriptor app = mAppsResolver.getAppByUid(conn.uid, 0);
        o.addProperty("app", (app != null) ? nullToEmpty(app.getName()) : "");

        if(includePayload) {
            JsonArray chunks = new JsonArray();
            int numChunks = Math.min(conn.getNumPayloadChunks(), MAX_PAYLOAD_CHUNKS);
            for(int i = 0; i < numChunks; i++) {
                PayloadChunk chunk = conn.readPayloadChunk(i, MAX_CHUNK_BYTES);
                if(chunk == null)
                    continue;

                JsonObject c = obj();
                c.addProperty("index", i);
                c.addProperty("type", (chunk.type != null) ? chunk.type.name() : "UNKNOWN");
                c.addProperty("sent", chunk.is_sent);
                c.addProperty("timestamp", chunk.timestamp);
                if(chunk.payload != null) {
                    String text = new String(chunk.payload, StandardCharsets.UTF_8).replace("\u0000", "");
                    c.addProperty("text", text);
                    c.addProperty("length", chunk.payload.length);
                }
                chunks.add(c);
            }
            o.add("payload_chunks", chunks);
            o.addProperty("payload_truncated", conn.getNumPayloadChunks() > MAX_PAYLOAD_CHUNKS);
        }
        return o;
    }

    /* ------------------------- JSON helpers ------------------------- */

    private static JsonObject obj() {
        return new JsonObject();
    }

    private static JsonObject props(JsonObject... properties) {
        JsonObject p = obj();
        for(int i = 0; i < properties.length; i++) {
            for(java.util.Map.Entry<String, JsonElement> e : properties[i].entrySet())
                p.add(e.getKey(), e.getValue());
        }
        return p;
    }

    private static JsonObject property(String name, String type, String description) {
        JsonObject p = obj();
        p.addProperty("type", type);
        p.addProperty("description", description);
        JsonObject wrapper = obj();
        wrapper.add(name, p);
        return wrapper;
    }

    private static JsonArray stringArray(String[] values) {
        JsonArray a = new JsonArray();
        for(String v : values)
            a.add(new JsonPrimitive(v));
        return a;
    }

    private static JsonObject tool(String name, String description, JsonObject schema) {
        JsonObject t = obj();
        t.addProperty("name", name);
        t.addProperty("description", description);
        schema.addProperty("type", "object");
        t.add("inputSchema", schema);
        return t;
    }

    private static JsonObject textResult(String text) {
        JsonObject res = obj();
        JsonArray content = new JsonArray();
        JsonObject item = obj();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        content.add(item);
        res.add("content", content);
        res.addProperty("isError", false);
        return res;
    }

    private static JsonObject errorResult(String message) {
        JsonObject res = textResult(message);
        res.addProperty("isError", true);
        return res;
    }

    private static String nullToEmpty(String s) {
        return (s != null) ? s : "";
    }
}
