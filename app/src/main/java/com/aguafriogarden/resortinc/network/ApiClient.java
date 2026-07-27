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
    private static final String STORAGE_BASE_URL = "https://aguafriogardenresortinc.com/storage/";

    private static final Gson gson = new Gson();

    private static AuthApi authApi;
    private static BookingApi bookingApi;

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

    /** Full URL for an image_path returned by the backend (e.g. "rooms/abc.jpg"), or null if there is none. */
    public static String imageUrl(String imagePath) {
        if (imagePath == null || imagePath.trim().isEmpty()) {
            return null;
        }
        return STORAGE_BASE_URL + imagePath;
    }

    public static synchronized AuthApi authApi() {
        if (authApi == null) {
            authApi = retrofit().create(AuthApi.class);
        }
        return authApi;
    }

    public static synchronized BookingApi bookingApi() {
        if (bookingApi == null) {
            bookingApi = retrofit().create(BookingApi.class);
        }
        return bookingApi;
    }

    private static Retrofit retrofit() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();

        return new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
    }
}
