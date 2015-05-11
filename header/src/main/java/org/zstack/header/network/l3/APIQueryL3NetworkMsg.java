package org.zstack.header.network.l3;

import org.zstack.header.query.APIQueryMessage;
import org.zstack.header.query.AutoQuery;

@AutoQuery(replyClass = APIQueryL3NetworkReply.class, inventoryClass = L3NetworkInventory.class)
public class APIQueryL3NetworkMsg extends APIQueryMessage {

}
