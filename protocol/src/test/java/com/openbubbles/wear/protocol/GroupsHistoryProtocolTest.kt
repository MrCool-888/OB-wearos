package com.openbubbles.wear.protocol

import org.junit.Assert.*
import org.junit.Test

class GroupsHistoryProtocolTest {
    @Test fun groupGuidValidationDoesNotInferGroupsFromNames() {
        assertTrue(Protocol.isGroupGuid("iMessage;+;chat123"))
        assertTrue(Protocol.isGroupGuid("SMS;+;1234"))
        assertTrue(Protocol.isGroupGuid("RCS;+;1234"))
        assertFalse(Protocol.isGroupGuid("iMessage;-;alex@example.com"))
        assertFalse(Protocol.isGroupGuid("Family group"))
        assertFalse(Protocol.isGroupGuid("iMessage;+;bad\nvalue"))
        assertFalse(Protocol.isChatGuid("iMessage;+;"))
    }
    @Test fun discoveryRequestAndResultRoundTrip() {
        val request = GroupsRequest(requestId = "r")
        assertEquals(request, Wire.decodeBytes<GroupsRequest>(Wire.encodeBytes(request)))
        val result = GroupsResult(requestId = "r", groups = listOf(WireThread(id = "iMessage;+;chat1", title = "Family", isGroup = true).toSummary()), note = "Partial")
        assertEquals(result, Wire.decodeBytes<GroupsResult>(Wire.encodeBytes(result)))
    }
    @Test fun historyWindowAnchorRoundTripsAndOldRequestsRemainCompatible() {
        val request = HistoryRequest(requestId = "r", threadId = "iMessage;+;chat1", isGroup = true,
            anchorMessages = listOf(WireMessage(id = "m", threadId = "iMessage;+;chat1", text = "Anchor", timestamp = 1000)))
        assertEquals(request, Wire.decodeBytes<HistoryRequest>(Wire.encodeBytes(request)))
        val legacy = Wire.decode<HistoryRequest>("{\"requestId\":\"old\",\"threadId\":\"chat\"}")!!
        assertNull(legacy.anchorMessages)
        val empty = request.copy(anchorMessages = emptyList())
        assertEquals(empty, Wire.decodeBytes<HistoryRequest>(Wire.encodeBytes(empty)))
    }
}
