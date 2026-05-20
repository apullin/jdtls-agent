package dev.jdtlsagent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentProtocolTest {
    @Test
    void translatesJsonRpcMethodAndNamedParamsToServiceRequest() {
        JsonObject rpc = JsonParser.parseString("""
                {
                  "jsonrpc": "2.0",
                  "id": 7,
                  "method": "jdtlsAgent.references",
                  "params": {
                    "query": "toy.Foo.bar",
                    "includeDeclaration": true,
                    "limit": 12
                  }
                }
                """).getAsJsonObject();

        JsonObject request = AgentProtocol.toServiceRequest(rpc);

        assertEquals("references", request.get("command").getAsString());
        assertEquals("toy.Foo.bar", request.getAsJsonArray("args").get(0).getAsString());
        assertTrue(request.getAsJsonObject("options").get("includeDeclaration").getAsBoolean());
        assertEquals(12, request.getAsJsonObject("options").get("limit").getAsInt());
    }

    @Test
    void translatesJsonRpcPositionalParams() {
        JsonObject rpc = JsonParser.parseString("""
                {
                  "jsonrpc": "2.0",
                  "id": "abc",
                  "method": "jdtls-agent/callees",
                  "params": ["toy.Foo.bar"]
                }
                """).getAsJsonObject();

        JsonObject request = AgentProtocol.toServiceRequest(rpc);

        assertEquals("callees", request.get("command").getAsString());
        assertEquals("toy.Foo.bar", request.getAsJsonArray("args").get(0).getAsString());
    }

    @Test
    void preservesLegacyCommandObjects() {
        JsonObject legacy = JsonParser.parseString("""
                {"command":"status","args":[],"options":{"limit":5}}
                """).getAsJsonObject();

        JsonObject request = AgentProtocol.toServiceRequest(legacy);

        assertEquals("status", request.get("command").getAsString());
        assertEquals(5, request.getAsJsonObject("options").get("limit").getAsInt());
    }

    @Test
    void detectsNotificationsAsJsonRpcWithoutId() {
        JsonObject notification = JsonParser.parseString("""
                {"jsonrpc":"2.0","method":"jdtlsAgent.status","params":{}}
                """).getAsJsonObject();

        assertTrue(AgentProtocol.isJsonRpc(notification));
        assertFalse(notification.has("id"));
    }
}
