package com.UARTLoopback;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.POST;

public interface MyApi {
    @FormUrlEncoded
    @POST("insertdata.php")
    Call<ResponseBody>insertdata(
            @Field("time") String time,
            @Field("mac") String mac,
            @Field("temp") String temp,
            @Field("hum") String hum,
            @Field("bio") String bio,
            @Field("pir") String pir,
            @Field("door") String door,
            @Field("fire") String fire,
            @Field("p_btn") String p_btn
    );
}