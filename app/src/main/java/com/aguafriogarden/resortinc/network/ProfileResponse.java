package com.aguafriogarden.resortinc.network;

public class ProfileResponse {
    public int user_ID;
    public String username;
    public String email;
    public String Firstname;
    public String Middlename;
    public String Lastname;
    public String gender;
    public String birthday;
    public String contact_number;
    public String address;
    public String profile_image;
    /** True once an ID photo is on file (from sign-up or the Valid ID screen). */
    public boolean has_valid_id;
    public String valid_id_type;
}
