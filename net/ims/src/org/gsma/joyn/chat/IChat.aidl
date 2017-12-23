package org.gsma.joyn.chat;

import org.gsma.joyn.chat.IChatListener;
import org.gsma.joyn.chat.Geoloc;

/**
 * Chat interface
 */
interface IChat {
	String getRemoteContact();
	

	String sendMessage(in String message);
	
	void sendDisplayedDeliveryReport(in String msgId);
	
	void sendIsComposingEvent(in boolean status);
	
	void addEventListener(in IChatListener listener);
	
	void removeEventListener(in IChatListener listener);

	String sendGeoloc(in Geoloc geoloc);
	
	int resendMessage(in String msgId);
	
	int reSendMultiMessageByPagerMode(in String msgId);
	
	String sendMessageByLargeMode(in String message);
	
	String sendPublicAccountMessageByLargeMode(in String message);
	
	String sendMessageByPagerMode(in String message ,in boolean isBurnMessage , in boolean isPublicMessage , in boolean isMultiMessage , in boolean isPayEmoticon,in List<String> participants);
	
	String sendOnetoMultiMessage(in String message, in List<String> participants);
	
	String sendEmoticonShopMessage(in String message);
	
	String sendPagerModeBurnMessage(in String message);
	
	String sendLargeModeBurnMessage(in String message);
	
	int getState(in String msgId);
	
	void sendBurnDeliveryReport(in String msgId);
}
