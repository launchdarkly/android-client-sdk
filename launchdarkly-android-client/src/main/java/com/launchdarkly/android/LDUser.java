package com.launchdarkly.android;


import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.Expose;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * A {@code LDUser} object contains specific attributes of a user browsing your site. The only mandatory property property is the {@code key},
 * which must uniquely identify each user. For authenticated users, this may be a username or e-mail address. For anonymous users,
 * this could be an IP address or session ID.
 * <p/>
 * Besides the mandatory {@code key}, {@code LDUser} supports two kinds of optional attributes: interpreted attributes (e.g. {@code ip} and {@code country})
 * and custom attributes.  LaunchDarkly can parse interpreted attributes and attach meaning to them. For example, from an {@code ip} address, LaunchDarkly can
 * do a geo IP lookup and determine the user's country.
 * <p/>
 * Custom attributes are not parsed by LaunchDarkly. They can be used in custom rules-- for example, a custom attribute such as "customer_ranking" can be used to
 * launch a feature to the top 10% of users on a site.
 */
public class LDUser {
    private static final UserHasher USER_HASHER = new UserHasher();

    private static final String KEY = "key";
    private static final String ANONYMOUS = "anonymous";
    private static final String CUSTOM = "custom";
    private static final String DEVICE = "device";
    private static final String OS = "os";

    static final String IP = "ip";
    static final String COUNTRY = "country";
    static final String SECONDARY = "secondary";
    static final String FIRST_NAME = "firstName";
    static final String LAST_NAME = "lastName";
    static final String EMAIL = "email";
    static final String NAME = "name";
    static final String AVATAR = "avatar";

    @Expose
    private final JsonPrimitive key;
    @Expose
    private final JsonPrimitive anonymous;

    @Expose
    private final JsonPrimitive secondary;
    @Expose
    private final JsonPrimitive ip;
    @Expose
    private final JsonPrimitive email;
    @Expose
    private final JsonPrimitive name;
    @Expose
    private final JsonPrimitive avatar;
    @Expose
    private final JsonPrimitive firstName;
    @Expose
    private final JsonPrimitive lastName;
    @Expose
    private final JsonPrimitive country;
    @Expose
    private final Map<String, JsonElement> custom;

    @NonNull
    @Expose(deserialize = false, serialize = false)
    private final Set<String> privateAttributeNames;

    @Expose(deserialize = false, serialize = false)
    private final String urlSafeBase64;

    @Expose(deserialize = false, serialize = false)
    private final String sharedPrefsKey;


    protected LDUser(Builder builder) {
        if (builder.key == null || builder.key.equals("")) {
            Timber.w("User was created with null/empty key. Using device-unique anonymous user key: %s", LDClient.getInstanceId());
            this.key = new JsonPrimitive(LDClient.getInstanceId());
            this.anonymous = new JsonPrimitive(true);
        } else {
            this.key = new JsonPrimitive(builder.key);
            this.anonymous = builder.anonymous == null ? null : new JsonPrimitive(builder.anonymous);
        }

        this.ip = builder.ip == null ? null : new JsonPrimitive(builder.ip);
        this.country = builder.country == null ? null : new JsonPrimitive(builder.country.getAlpha2());
        this.secondary = builder.secondary == null ? null : new JsonPrimitive(builder.secondary);
        this.firstName = builder.firstName == null ? null : new JsonPrimitive(builder.firstName);
        this.lastName = builder.lastName == null ? null : new JsonPrimitive(builder.lastName);
        this.email = builder.email == null ? null : new JsonPrimitive(builder.email);
        this.name = builder.name == null ? null : new JsonPrimitive(builder.name);
        this.avatar = builder.avatar == null ? null : new JsonPrimitive(builder.avatar);
        this.custom = Collections.unmodifiableMap(builder.custom);

        this.privateAttributeNames = builder.privateAttributeNames;

        String userJson = getJson();
        this.urlSafeBase64 = Base64.encodeToString(userJson.getBytes(), Base64.URL_SAFE + Base64.NO_WRAP);
        this.sharedPrefsKey = USER_HASHER.hash(userJson);

    }

    @VisibleForTesting
    String getJson() {
        return LDConfig.GSON.toJson(this);
    }

    String getAsUrlSafeBase64() {
        return urlSafeBase64;
    }

    JsonPrimitive getKey() {
        return key;
    }

    String getKeyAsString() {
        if (key == null) {
            return "";
        } else {
            return key.getAsString();
        }
    }

    JsonPrimitive getIp() {
        return ip;
    }

    JsonPrimitive getCountry() {
        return country;
    }

    JsonPrimitive getSecondary() {
        return secondary;
    }

    JsonPrimitive getName() {
        return name;
    }

    JsonPrimitive getFirstName() {
        return firstName;
    }

    JsonPrimitive getLastName() {
        return lastName;
    }

    JsonPrimitive getEmail() {
        return email;
    }

    JsonPrimitive getAvatar() {
        return avatar;
    }

    JsonPrimitive getAnonymous() {
        return anonymous;
    }

    JsonElement getCustom(String key) {
        if (custom != null) {
            return custom.get(key);
        }
        return null;
    }

    @NonNull
    Set<String> getPrivateAttributeNames() {
        return Collections.unmodifiableSet(privateAttributeNames);
    }

    String getSharedPrefsKey() {
        return sharedPrefsKey;
    }

    /**
     * A <a href="http://en.wikipedia.org/wiki/Builder_pattern">builder</a> that helps construct {@link LDUser} objects. Builder
     * calls can be chained, enabling the following pattern:
     * <p/>
     * <pre>
     * LDUser user = new LDUser.Builder("key")
     *      .country("US")
     *      .ip("192.168.0.1")
     *      .build()
     * </pre>
     */
    public static class Builder {

        private String key;
        private Boolean anonymous;

        private String secondary;
        private String ip;
        private String firstName;
        private String lastName;
        private String email;
        private String name;
        private String avatar;
        private LDCountryCode country;

        private final Map<String, JsonElement> custom;

        @NonNull
        private final Set<String> privateAttributeNames;

        /**
         * Create a builder with the specified key
         *
         * @param key the unique key for this user
         */
        public Builder(String key) {
            this.key = key;
            this.custom = new HashMap<>();

            custom.put(LDUser.OS, new JsonPrimitive(Build.VERSION.SDK_INT));
            custom.put(LDUser.DEVICE, new JsonPrimitive(Build.MODEL + " " + Build.PRODUCT));
            privateAttributeNames = new HashSet<>();
        }

        public Builder(LDUser user) {
            JsonPrimitive userKey = user.getKey();
            if (userKey.isJsonNull()) {
                this.key = null;
            } else {
                this.key = user.getKeyAsString();
            }
            this.anonymous = user.getAnonymous() != null ? user.getAnonymous().getAsBoolean() : null;

            this.secondary = user.getSecondary() != null ? user.getSecondary().getAsString() : null;
            this.ip = user.getIp() != null ? user.getIp().getAsString() : null;
            this.firstName = user.getFirstName() != null ? user.getFirstName().getAsString() : null;
            this.lastName = user.getLastName() != null ? user.getLastName().getAsString() : null;
            this.email = user.getEmail() != null ? user.getEmail().getAsString() : null;
            this.name = user.getName() != null ? user.getName().getAsString() : null;
            this.avatar = user.getAvatar() != null ? user.getAvatar().getAsString() : null;
            this.country = user.getCountry() != null ? LDCountryCode.valueOf(user.getCountry().getAsString()) : null;
            this.custom = new HashMap<>(user.custom);

            this.privateAttributeNames = new HashSet<>(user.getPrivateAttributeNames());
        }

        /**
         * Set the IP for a user
         *
         * @param s the IP address for the user
         * @return the builder
         */
        public Builder ip(String s) {
            this.ip = s;
            return this;
        }

        /**
         * Set the IP for a user
         * Private attributes are not sent to the server.
         *
         * @param s the IP address for the user
         * @return the builder
         */
        public Builder privateIp(String s) {
            privateAttributeNames.add(IP);
            return ip(s);
        }

        public Builder secondary(String s) {
            this.secondary = s;
            return this;
        }

        public Builder privateSecondary(String s) {
            privateAttributeNames.add(SECONDARY);
            return secondary(s);
        }

        /**
         * Set the country for a user. The country should be a valid <a href="http://en.wikipedia.org/wiki/ISO_3166-1">ISO 3166-1</a>
         * alpha-2 or alpha-3 code. If it is not a valid ISO-3166-1 code, an attempt will be made to look up the country by its name.
         * If that fails, a warning will be logged, and the country will not be set.
         *
         * @param s the country for the user
         * @return the builder
         */
        public Builder country(String s) {
            this.country = countryCode(s);
            return this;
        }


        /**
         * Set the country for a user. The country should be a valid <a href="http://en.wikipedia.org/wiki/ISO_3166-1">ISO 3166-1</a>
         * alpha-2 or alpha-3 code. If it is not a valid ISO-3166-1 code, an attempt will be made to look up the country by its name.
         * If that fails, a warning will be logged, and the country will not be set.
         * Private attributes are not sent to the server.
         *
         * @param s the country for the user
         * @return the builder
         */
        public Builder privateCountry(String s) {
            privateAttributeNames.add(COUNTRY);
            return country(s);
        }

        private LDCountryCode countryCode(String s) {
            LDCountryCode countryCode = LDCountryCode.getByCode(s, false);

            if (countryCode == null) {
                List<LDCountryCode> codes = LDCountryCode.findByName("^" + Pattern.quote(s) + ".*");

                if (codes.isEmpty()) {
                    Timber.w("Invalid country. Expected valid ISO-3166-1 code: %s", s);
                } else if (codes.size() > 1) {
                    // See if any of the codes is an exact match
                    for (LDCountryCode c : codes) {
                        if (c.getName().equals(s)) {
                            countryCode = c;
                            return countryCode;
                        }
                    }
                    Timber.w("Ambiguous country. Provided code matches multiple countries: %s", s);
                    countryCode = codes.get(0);
                } else {
                    countryCode = codes.get(0);
                }

            }
            return countryCode;
        }

        /**
         * Set the country for a user.
         *
         * @param country the country for the user
         * @return the builder
         */
        public Builder country(LDCountryCode country) {
            this.country = country;
            return this;
        }

        /**
         * Set the country for a user.
         * Private attributes are not sent to the server.
         *
         * @param country the country for the user
         * @return the builder
         */
        public Builder privateCountry(LDCountryCode country) {
            privateAttributeNames.add(COUNTRY);
            return country(country);
        }

        /**
         * Sets the user's first name
         *
         * @param firstName the user's first name
         * @return the builder
         */
        public Builder firstName(String firstName) {
            this.firstName = firstName;
            return this;
        }

        /**
         * Sets the user's first name
         * Private attributes are not sent to the server.
         *
         * @param firstName the user's first name
         * @return the builder
         */
        public Builder privateFirstName(String firstName) {
            privateAttributeNames.add(FIRST_NAME);
            return firstName(firstName);
        }

        /**
         * Sets whether this user is anonymous
         *
         * @param anonymous whether the user is anonymous
         * @return the builder
         */
        public Builder anonymous(boolean anonymous) {
            this.anonymous = anonymous;
            return this;
        }

        /**
         * Sets the user's last name
         *
         * @param lastName the user's last name
         * @return the builder
         */
        public Builder lastName(String lastName) {
            this.lastName = lastName;
            return this;
        }

        /**
         * Sets the user's last name
         * Private attributes are not sent to the server.
         *
         * @param lastName the user's last name
         * @return the builder
         */
        public Builder privateLastName(String lastName) {
            privateAttributeNames.add(LAST_NAME);
            return lastName(lastName);
        }

        /**
         * Sets the user's full name
         *
         * @param name the user's full name
         * @return the builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the user's full name
         * Private attributes are not sent to the server.
         *
         * @param name the user's full name
         * @return the builder
         */
        public Builder privateName(String name) {
            privateAttributeNames.add(NAME);
            return name(name);
        }

        /**
         * Sets the user's avatar
         *
         * @param avatar the user's avatar
         * @return the builder
         */
        public Builder avatar(String avatar) {
            this.avatar = avatar;
            return this;
        }

        /**
         * Sets the user's avatar
         * Private attributes are not sent to the server.
         *
         * @param avatar the user's avatar
         * @return the builder
         */
        public Builder privateAvatar(String avatar) {
            privateAttributeNames.add(AVATAR);
            return avatar(avatar);
        }

        /**
         * Sets the user's e-mail address
         *
         * @param email the e-mail address
         * @return the builder
         */
        public Builder email(String email) {
            this.email = email;
            return this;
        }

        /**
         * Sets the user's e-mail address
         * Private attributes are not sent to the server.
         *
         * @param email the e-mail address
         * @return the builder
         */
        public Builder privateEmail(String email) {
            privateAttributeNames.add(EMAIL);
            return email(email);
        }

        /**
         * Add a {@link String}-valued custom attribute. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         *
         * @param k the key for the custom attribute.
         * @param v the value for the custom attribute
         * @return the builder
         */
        public Builder custom(String k, String v) {
            return custom(custom, k, new JsonPrimitive(v));
        }

        /**
         * Add a {@link String}-valued custom attribute. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         * Private attributes are not sent to the server.
         *
         * @param k the key for the custom attribute.
         * @param v the value for the custom attribute
         * @return the builder
         */
        public Builder privateCustom(String k, String v) {
            privateAttributeNames.add(k);
            return custom(k, v);
        }

        private <T> Builder custom(Map<String, T> map, String k, T v) {
            checkCustomAttribute(k);
            if (k != null && v != null) {
                map.put(k, v);
            }
            return this;
        }

        /**
         * Add a {@link Number}-valued custom attribute. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         *
         * @param k the key for the custom attribute. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
         * @param n the value for the custom attribute
         * @return the builder
         */
        public Builder custom(String k, Number n) {
            return custom(custom, k, new JsonPrimitive(n));
        }

        /**
         * Add a {@link Number}-valued custom attribute. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         * Private attributes are not sent to the server.
         *
         * @param k the key for the custom attribute. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
         * @param n the value for the custom attribute
         * @return the builder
         */
        public Builder privateCustom(String k, Number n) {
            privateAttributeNames.add(k);
            return custom(k, n);
        }

        /**
         * Add a {@link Boolean}-valued custom attribute. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         *
         * @param k the key for the custom attribute. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
         * @param b the value for the custom attribute
         * @return the builder
         */
        public Builder custom(String k, Boolean b) {
            return custom(custom, k, new JsonPrimitive(b));
        }

        /**
         * Add a {@link Boolean}-valued custom attribute. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         * Private attributes are not sent to the server.
         *
         * @param k the key for the custom attribute. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
         * @param b the value for the custom attribute
         * @return the builder
         */
        public Builder privateCustom(String k, Boolean b) {
            privateAttributeNames.add(k);
            return custom(k, b);
        }

        /**
         * Add a list of {@link String}-valued custom attributes. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         *
         * @param k  the key for the list. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
         * @param vs the values for the attribute
         * @return the builder
         * @deprecated As of version 0.16.0, renamed to {@link #customString(String, List) customString}
         */
        public Builder custom(String k, List<String> vs) {
            return custom(custom, k, vs);
        }

        /**
         * Add a list of {@link String}-valued custom attributes. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         *
         * @param k  the key for the list. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
         * @param vs the values for the attribute
         * @return the builder
         */
        public Builder customString(String k, List<String> vs) {
            return custom(custom, k, vs);
        }

        /**
         * Add a list of {@link String}-valued custom attributes. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         * Private attributes are not sent to the server.
         *
         * @param k  the key for the list. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
         * @param vs the values for the attribute
         * @return the builder
         */
        public Builder privateCustomString(String k, List<String> vs) {
            privateAttributeNames.add(k);
            return customString(k, vs);
        }

        private Builder custom(Map<String, JsonElement> map, String k, List<String> vs) {
            checkCustomAttribute(k);
            JsonArray array = new JsonArray();
            for (String v : vs) {
                if (v != null) {
                    array.add(new JsonPrimitive(v));
                }
            }
            custom.put(k, array);
            return this;
        }

        /**
         * Add a list of {@link Integer}-valued custom attributes. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         *
         * @param k  the key for the list. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
         * @param vs the values for the attribute
         * @return the builder
         */
        public Builder customNumber(String k, List<Number> vs) {
            return customNumber(custom, k, vs);
        }

        /**
         * Add a list of {@link Integer}-valued custom attributes. When set to one of the
         * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
         * built-in user attribute keys</a>, this custom attribute will be ignored.
         * Private attributes are not sent to the server.
         *
         * @param k  the key for the list. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
         * @param vs the values for the attribute
         * @return the builder
         */
        public Builder privateCustomNumber(String k, List<Number> vs) {
            privateAttributeNames.add(k);
            return customNumber(k, vs);
        }

        private Builder customNumber(Map<String, JsonElement> map, String k, List<Number> vs) {
            checkCustomAttribute(k);
            JsonArray array = new JsonArray();
            for (Number v : vs) {
                if (v != null) {
                    array.add(new JsonPrimitive(v));
                }
            }
            map.put(k, array);
            return this;
        }

        private void checkCustomAttribute(String key) {
            for (UserAttribute a : UserAttribute.values()) {
                if (a.name().equals(key)) {
                    Timber.w("Built-in attribute key: %s added as custom attribute! This custom attribute will be ignored during Feature Flag evaluation", key);
                    return;
                }
            }
        }

        @VisibleForTesting
        @NonNull
        Set<String> getPrivateAttributeNames() {
            return privateAttributeNames;
        }

        /**
         * Build the configured {@link LDUser} object
         *
         * @return the {@link LDUser} configured by this builder
         */
        public LDUser build() {
            return new LDUser(this);
        }

    }

    static class LDUserPrivateAttributesTypeAdapter extends TypeAdapter<LDUser> {

        static final String PRIVATE_ATTRS = "privateAttrs";

        private final LDConfig config;

        LDUserPrivateAttributesTypeAdapter(LDConfig config) {
            this.config = config;
        }

        @Override
        public void write(JsonWriter out, LDUser user) throws IOException {
            // Unless `inlineUsersInEvents` is true, there may be no user to write.
            if (user == null) {
                out.nullValue();
                return;
            }

            // Collect the private attribute names
            Set<String> privateAttributeNames = new HashSet<>(config.getPrivateAttributeNames());

            out.beginObject();
            // The key can never be private
            out.name(LDUser.KEY).value(user.getKeyAsString());

            if (user.getSecondary() != null) {
                if (!checkAndAddPrivate(LDUser.SECONDARY, user, privateAttributeNames)) {
                    out.name(LDUser.SECONDARY).value(user.getSecondary().getAsString());
                }
            }
            if (user.getIp() != null) {
                if (!checkAndAddPrivate(LDUser.IP, user, privateAttributeNames)) {
                    out.name(LDUser.IP).value(user.getIp().getAsString());
                }
            }
            if (user.getEmail() != null) {
                if (!checkAndAddPrivate(LDUser.EMAIL, user, privateAttributeNames)) {
                    out.name(LDUser.EMAIL).value(user.getEmail().getAsString());
                }
            }
            if (user.getName() != null) {
                if (!checkAndAddPrivate(LDUser.NAME, user, privateAttributeNames)) {
                    out.name(LDUser.NAME).value(user.getName().getAsString());
                }
            }
            if (user.getAvatar() != null) {
                if (!checkAndAddPrivate(LDUser.AVATAR, user, privateAttributeNames)) {
                    out.name(LDUser.AVATAR).value(user.getAvatar().getAsString());
                }
            }
            if (user.getFirstName() != null) {
                if (!checkAndAddPrivate(LDUser.FIRST_NAME, user, privateAttributeNames)) {
                    out.name(LDUser.FIRST_NAME).value(user.getFirstName().getAsString());
                }
            }
            if (user.getLastName() != null) {
                if (!checkAndAddPrivate(LDUser.LAST_NAME, user, privateAttributeNames)) {
                    out.name(LDUser.LAST_NAME).value(user.getLastName().getAsString());
                }
            }
            if (user.getAnonymous() != null) {
                out.name(LDUser.ANONYMOUS).value(user.getAnonymous().getAsBoolean());
            }
            if (user.getCountry() != null) {
                if (!checkAndAddPrivate(LDUser.COUNTRY, user, privateAttributeNames)) {
                    out.name(LDUser.COUNTRY).value(user.getCountry().getAsString());
                }
            }
            writeCustomAttrs(out, user, privateAttributeNames);
            writePrivateAttrNames(out, privateAttributeNames);

            out.endObject();

        }

        @Override
        public LDUser read(JsonReader in) throws IOException {
            return LDConfig.GSON.fromJson(in, LDUser.class);
        }

        private void writeCustomAttrs(JsonWriter out, LDUser user, Set<String> privateAttributeNames) throws IOException {
            boolean beganObject = false;
            if (user.custom == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : user.custom.entrySet()) {
                if (!checkAndAddPrivate(entry.getKey(), user, privateAttributeNames)) {
                    if (!beganObject) {
                        out.name(LDUser.CUSTOM);
                        out.beginObject();
                        beganObject = true;
                    }
                    out.name(entry.getKey());
                    // this accesses part of the internal GSON api. However, it's likely
                    // the only way to write a JsonElement directly:
                    // https://groups.google.com/forum/#!topic/google-gson/JpHbpZ9mTOk
                    Streams.write(entry.getValue(), out);
                }
            }
            if (beganObject) {
                out.endObject();
            }
        }

        private void writePrivateAttrNames(JsonWriter out, Set<String> names) throws IOException {
            if (names.isEmpty()) {
                return;
            }
            out.name(PRIVATE_ATTRS);
            out.beginArray();
            for (String name : names) {
                out.value(name);
            }
            out.endArray();
        }

        private boolean checkAndAddPrivate(String key, LDUser user, Set<String> privateAttrs) {
            boolean result = config.allAttributesPrivate()
                    || config.getPrivateAttributeNames().contains(key)
                    || user.getPrivateAttributeNames().contains(key);
            result = result && (!key.equals(LDUser.DEVICE) && !key.equals(LDUser.OS));

            if (result) {
                privateAttrs.add(key);
            }
            return result;
        }

    }
}
