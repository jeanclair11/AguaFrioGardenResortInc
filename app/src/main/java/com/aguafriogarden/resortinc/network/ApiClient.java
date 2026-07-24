package com.aguafriogarden.resortinc.network;

import com.google.gson.Gson;

import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Single Retrofit client for the live Agua Frio backend (same DB as the website). */
public final class ApiClient {

    private static final String BASE_URL = "https://aguafriogardenresortinc.com/api/";

    private static final Gson gson = new Gson();

    private static AuthApi authApi;

    private ApiClient() {
    }

    /** Parses a failed response's JSON body (Laravel's {message, errors} shape). */
    public static ErrorResponse parseError(ResponseBody errorBody) {
        if (errorBody == null) {
            return new ErrorResponse();
        }
        try {
            ErrorResponse error = gson.fromJson(errorBody.charStream(), ErrorResponse.class);
            return error != null ? error : new ErrorResponse();
        } catch (Exception e) {
            return new ErrorResponse();
        }
    }

    public static synchronized AuthApi authApi() {
        if (authApi == null) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .build();

            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();

            authApi = retrofit.create(AuthApi.class);
        }
        return authApi;
    }
}
