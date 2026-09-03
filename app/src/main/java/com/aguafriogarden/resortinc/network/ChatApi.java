package com.aguafriogarden.resortinc.network;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Query;

/**
 * Mirrors the website's receptionist Chat module (Api\ChatController on the
 * backend), reading and writing the exact same chat_conversations/
 * chat_messages rows so guest and receptionist see the same conversation.
 */
public interface ChatApi {

    @GET("chat")
    Call<ChatHistoryResponse> getConversation(@Header("Authorization") String bearerToken);

    @GET("chat/poll")
    Call<ChatHistoryResponse> poll(@Header("Authorization") String bearerToken, @Query("after") long afterId);

    @FormUrlEncoded
    @POST("chat/send")
    Call<ChatSendResponse> send(@Header("Authorization") String bearerToken, @Field("body") String body);
}
