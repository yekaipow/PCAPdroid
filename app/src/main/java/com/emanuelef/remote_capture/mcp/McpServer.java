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

import com.emanuelef.remote_capture.BuildConfig;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.Utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/* A small HTTP server implementing the MCP (Model Context Protocol) Streamable HTTP
 * transport over JSON-RPC 2.0.
 *
 * Endpoints:
 *  - POST /mcp     : JSON-RPC 2.0 requests (initialize, tools/list, tools/call, ping)
 *  - GET  /health  : simple liveness probe
 *
 * The server binds to loopback by default and optionally requires a bearer token.
 * This avoids exposing the capture control to the local network unless explicitly enabled.
 */
public class McpServer implements Runnable {
    private static final String TAG = "McpServer";
    private static final String MCP_PATH = "/mcp";
    private static final String HEALTH_PATH = "/health";
    // The newest protocol version this server speaks (Streamable HTTP transport).
    private static final String PROTOCOL_VERSION = "2025-03-26";
    // Protocol versions we accept from clients during initialization.
    private static final String[] SUPPORTED_VERSIONS = {
            "2025-06-18", "2025-03-26", "2024-11-05"
    };
    private static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    private static final int MAX_CLIENTS = 8;
    private static final int MAX_BODY_SIZE = 1024 * 1024; // 1 MB

    private final Context mContext;
    private final int mPort;
    private final boolean mBindAll;
    private final String mToken;
    private final McpTools mTools;

    private ServerSocket mSocket;
    private volatile boolean mRunning;
    private Thread mThread;
    private ExecutorService mPool;
    // Track issued session ids so we can accept them on subsequent requests.
    private final Map<String, Boolean> mSessions = new ConcurrentHashMap<>();

    public McpServer(Context context, int port, boolean bindAll, String token) {
        mContext = context.getApplicationContext();
        mPort = port;
        mBindAll = bindAll;
        mToken = (token != null) ? token : "";
        mTools = new McpTools(mContext);
    }

    public int getPort() {
        return mPort;
    }

    public void start() throws IOException {
        if(mRunning)
            return;

        mSocket = new ServerSocket();
        mSocket.setReuseAddress(true);
        if(mBindAll)
            mSocket.bind(new InetSocketAddress(mPort));
        else
            mSocket.bind(new InetSocketAddress("127.0.0.1", mPort));

        mRunning = true;
        mPool = Executors.newFixedThreadPool(MAX_CLIENTS);
        mThread = new Thread(this, "McpServer");
        mThread.start();

        Log.i(TAG, "MCP server listening on " + (mBindAll ? "0.0.0.0" : "127.0.0.1") + ":" + mPort);
    }

    public void stop() {
        mRunning = false;
        Utils.safeClose(mSocket);

        if(mPool != null) {
            mPool.shutdown();
            try {
                mPool.awaitTermination(2, TimeUnit.SECONDS);
            } catch(InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mPool.shutdownNow();
        }

        if((mThread != null) && mThread.isAlive()) {
            try {
                mThread.join(2000);
            } catch(InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Log.i(TAG, "MCP server stopped");
    }

    @Override
    public void run() {
        while(mRunning) {
            try {
                Socket client = mSocket.accept();
                try {
                    mPool.execute(new ClientHandler(client));
                } catch(RejectedExecutionException e) {
                    Utils.safeClose(client);
                }
            } catch(IOException e) {
                if(mRunning)
                    Log.w(TAG, "Accept failed: " + e.getLocalizedMessage());
            }
        }
        Utils.safeClose(mSocket);
    }

    private class ClientHandler implements Runnable {
        private final Socket mClient;

        ClientHandler(Socket client) {
            mClient = client;
        }

        @Override
        public void run() {
            try {
                mClient.setSoTimeout(30000);
                InputStream in = mClient.getInputStream();
                OutputStream out = mClient.getOutputStream();

                // Read the request line
                String requestLine = readLine(in);
                if(requestLine == null)
                    return;

                String[] parts = requestLine.split(" ");
                if(parts.length < 2) {
                    sendResponse(out, 400, "Bad Request", "text/plain", "Bad request line");
                    return;
                }
                String method = parts[0].toUpperCase(Locale.ROOT);
                String url = parts[1];
                int qpos = url.indexOf('?');
                if(qpos >= 0)
                    url = url.substring(0, qpos);

                // Read headers
                String auth = null;
                String sessionId = null;
                String protocolVersion = null;
                boolean expectContinue = false;
                int contentLength = 0;
                String line;
                while((line = readLine(in)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if(colon < 0)
                        continue;
                    String name = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if(name.equalsIgnoreCase("Authorization"))
                        auth = value;
                    else if(name.equalsIgnoreCase(HEADER_SESSION_ID))
                        sessionId = value;
                    else if(name.equalsIgnoreCase("MCP-Protocol-Version"))
                        protocolVersion = value;
                    else if(name.equalsIgnoreCase("Expect") && value.equalsIgnoreCase("100-continue"))
                        expectContinue = true;
                    else if(name.equalsIgnoreCase("Content-Length")) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch(NumberFormatException ignored) {}
                    }
                }

                // Honour "Expect: 100-continue": tell the client to send the body.
                // Without this, strict HTTP clients wait for the interim response
                // and eventually time out before ever sending the JSON-RPC payload.
                if(expectContinue) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }

                // Authentication
                if(!mToken.isEmpty()) {
                    String expected = "Bearer " + mToken;
                    if(!expected.equals(auth)) {
                        sendResponse(out, 401, "Unauthorized", "text/plain",
                                "Invalid or missing bearer token");
                        return;
                    }
                }

                // Validate a session id supplied on a non-initialize request
                if(sessionId != null && !mSessions.containsKey(sessionId)) {
                    // The session is unknown/expired: ask the client to start a new one
                    sendResponse(out, 404, "Not Found", "text/plain", "Session not found");
                    return;
                }

                if(method.equals("GET") && url.equals(HEALTH_PATH)) {
                    JsonObject health = new JsonObject();
                    health.addProperty("status", "ok");
                    health.addProperty("server", "pcapdroid-mcp");
                    health.addProperty("version", BuildConfig.VERSION_NAME);
                    sendResponse(out, 200, "OK", "application/json", health.toString());
                    return;
                }

                // Streamable HTTP: GET on the MCP endpoint offers an SSE stream.
                // We don't push server-initiated messages, so report 405 as allowed by the spec.
                if(method.equals("GET") && url.equals(MCP_PATH)) {
                    sendResponse(out, 405, "Method Not Allowed", "text/plain",
                            "This server does not offer an SSE stream");
                    return;
                }

                // A client may explicitly terminate its session with DELETE.
                if(method.equals("DELETE") && url.equals(MCP_PATH)) {
                    if(sessionId != null)
                        mSessions.remove(sessionId);
                    sendResponse(out, 204, "No Content", null, null);
                    return;
                }

                if(!method.equals("POST") || !url.equals(MCP_PATH)) {
                    sendResponse(out, 404, "Not Found", "text/plain", "Not found");
                    return;
                }

                if(contentLength <= 0 || contentLength > MAX_BODY_SIZE) {
                    sendResponse(out, 400, "Bad Request", "text/plain", "Invalid body size");
                    return;
                }

                byte[] body = new byte[contentLength];
                int read = 0;
                while(read < contentLength) {
                    int n = in.read(body, read, contentLength - read);
                    if(n < 0)
                        break;
                    read += n;
                }

                String requestBody = new String(body, 0, read, StandardCharsets.UTF_8);
                boolean isInitialize = requestBody.contains("\"initialize\"");
                String newSession = null;
                if(isInitialize) {
                    newSession = UUID.randomUUID().toString();
                    mSessions.put(newSession, Boolean.TRUE);
                }

                String response = handleJsonRpc(requestBody, protocolVersion);
                if(response != null)
                    sendResponse(out, 200, "OK", "application/json", response, newSession);
                else
                    // Notifications have no response body
                    sendResponse(out, 202, "Accepted", null, null);
            } catch(IOException e) {
                Log.d(TAG, "Client error: " + e.getLocalizedMessage());
            } finally {
                Utils.safeClose(mClient);
            }
        }
    }

    /* Handles a JSON-RPC 2.0 body: a single message or a batch array.
     * Returns null only when all messages are notifications (no response body). */
    private String handleJsonRpc(String body, String headerProtocolVersion) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if(parsed.isJsonArray()) {
                com.google.gson.JsonArray batch = parsed.getAsJsonArray();
                if(batch.size() == 0)
                    return jsonRpcError(null, -32600, "Invalid Request: empty batch");

                com.google.gson.JsonArray responses = new com.google.gson.JsonArray();
                for(JsonElement element : batch) {
                    String r = handleSingle(element, headerProtocolVersion);
                    if(r != null)
                        responses.add(JsonParser.parseString(r));
                }
                // Only notifications in the batch -> no response at all
                return responses.size() == 0 ? null : responses.toString();
            }
            return handleSingle(parsed, headerProtocolVersion);
        } catch(Exception e) {
            Log.e(TAG, "JSON-RPC error: " + e);
            return jsonRpcError(null, -32700, "Parse error: " + e.getLocalizedMessage());
        }
    }

    /* Handles a single JSON-RPC message. Returns null for notifications. */
    private String handleSingle(JsonElement parsed, String headerProtocolVersion) {
        JsonObject response;
        try {
            if(!parsed.isJsonObject())
                return jsonRpcError(null, -32600, "Invalid Request");

            JsonObject req = parsed.getAsJsonObject();
            JsonElement id = req.has("id") ? req.get("id") : null;
            boolean notification = (id == null);

            if(!req.has("method") || !req.get("method").isJsonPrimitive())
                return jsonRpcError(id, -32600, "Invalid Request");

            String method = req.get("method").getAsString();
            JsonObject params = (req.has("params") && req.get("params").isJsonObject())
                    ? req.get("params").getAsJsonObject() : new JsonObject();

            JsonObject result = dispatch(method, params, headerProtocolVersion);
            if(result == null) {
                // unknown method
                return jsonRpcError(id, -32601, "Method not found: " + method);
            }

            if(notification)
                return null;

            response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", id);
            response.add("result", result);
            return response.toString();
        } catch(Exception e) {
            Log.e(TAG, "JSON-RPC error: " + e);
            return jsonRpcError(null, -32700, "Parse error: " + e.getLocalizedMessage());
        }
    }

    private JsonObject dispatch(String method, JsonObject params, String headerProtocolVersion) {
        switch(method) {
            case "initialize": {
                // Echo back a version the client supports. Modern clients reject a
                // mismatched/obsolete version and may then hang waiting for a legacy
                // SSE endpoint. Prefer the version requested in the params, then the
                // MCP-Protocol-Version header, falling back to our latest.
                String requested = null;
                if(params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive())
                    requested = params.get("protocolVersion").getAsString();
                if(requested == null)
                    requested = headerProtocolVersion;

                String negotiated = PROTOCOL_VERSION;
                if(requested != null) {
                    for(String supported : SUPPORTED_VERSIONS) {
                        if(supported.equals(requested)) {
                            negotiated = requested;
                            break;
                        }
                    }
                }

                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", negotiated);
                JsonObject capabilities = new JsonObject();
                capabilities.add("tools", new JsonObject());
                result.add("capabilities", capabilities);
                JsonObject serverInfo = new JsonObject();
                serverInfo.addProperty("name", "pcapdroid-mcp");
                serverInfo.addProperty("version", BuildConfig.VERSION_NAME);
                result.add("serverInfo", serverInfo);
                return result;
            }
            case "notifications/initialized":
            case "notifications/cancelled":
                // handled as notification by the caller (no result object needed)
                return new JsonObject();
            case "ping":
                return new JsonObject();
            case "tools/list": {
                JsonObject result = new JsonObject();
                result.add("tools", mTools.getToolDefinitions());
                return result;
            }
            case "tools/call": {
                if(!params.has("name") || !params.get("name").isJsonPrimitive())
                    throw new IllegalArgumentException("Missing tool name");
                String name = params.get("name").getAsString();
                JsonElement args = params.has("arguments") ? params.get("arguments") : null;
                return mTools.callTool(name, args);
            }
            default:
                return null;
        }
    }

    private static String jsonRpcError(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if(id != null)
            response.add("id", id);
        else
            response.add("id", com.google.gson.JsonNull.INSTANCE);

        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response.toString();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while((b = in.read()) != -1) {
            if(b == '\n')
                break;
            if(b != '\r')
                buf.write(b);
        }
        if(b == -1 && buf.size() == 0)
            return null;
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    private static void sendResponse(OutputStream out, int code, String reason,
                                     String contentType, String body) throws IOException {
        sendResponse(out, code, reason, contentType, body, null);
    }

    private static void sendResponse(OutputStream out, int code, String reason,
                                     String contentType, String body, String sessionId) throws IOException {
        byte[] bodyBytes = (body != null) ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(code).append(" ").append(reason).append("\r\n");
        if(contentType != null)
            header.append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n");
        header.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        if(sessionId != null)
            header.append(HEADER_SESSION_ID).append(": ").append(sessionId).append("\r\n");
        header.append("Connection: close\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        if(bodyBytes.length > 0) {
            out.write(bodyBytes);
        }
        out.flush();
    }
}
