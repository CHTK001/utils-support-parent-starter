package com.chua.common.support.datasearch.video.spi.data;

/**
 * TMDB 国家数据
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TmdbCountry {
    /**
     * 创建 tmdbcountry 实例
    */
    private TmdbCountry() {
    }

    /**
     * 通过 ISO-3166-1 两位字母代码获取本地名称
     *
     * @param iso 大小写不敏感
     * @return 对应的本地名称，未找到返回 null
     */
    public static String getNativeName(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Code.valueOf(iso.toUpperCase()).nativeName();
        } catch (IllegalArgumentException ignore) {
            return null;
        }
    }

    /**
     * 与国家代码一一对应的枚举，按需增减
     * @author CH
     * @since 4.0.0
     */
    public enum Code {
        /** 国家/地区代码 AD。 */
        AD("Andorra"),
        /** 国家/地区代码 AE。 */
        AE("United Arab Emirates"),
        /** 国家/地区代码 AF。 */
        AF("Afghanistan"),
        /** 国家/地区代码 AG。 */
        AG("Antigua & Barbuda"),
        /** 国家/地区代码 AI。 */
        AI("Anguilla"),
        /** 国家/地区代码 AL。 */
        AL("Albania"),
        /** 国家/地区代码 AM。 */
        AM("Armenia"),
        /** 国家/地区代码 AN。 */
        AN("Netherlands Antilles"),
        /** 国家/地区代码 AO。 */
        AO("Angola"),
        /** 国家/地区代码 AQ。 */
        AQ("Antarctica"),
        /** 国家/地区代码 AR。 */
        AR("Argentina"),
        /** 国家/地区代码 AS。 */
        AS("American Samoa"),
        /** 国家/地区代码 AT。 */
        AT("Austria"),
        /** 国家/地区代码 AU。 */
        AU("Australia"),
        /** 国家/地区代码 AW。 */
        AW("Aruba"),
        /** 国家/地区代码 AZ。 */
        AZ("Azerbaijan"),
        /** 国家/地区代码 BA。 */
        BA("Bosnia & Herzegovina"),
        /** 国家/地区代码 BB。 */
        BB("Barbados"),
        /** 国家/地区代码 BD。 */
        BD("Bangladesh"),
        /** 国家/地区代码 BE。 */
        BE("Belgium"),
        /** 国家/地区代码 BF。 */
        BF("Burkina Faso"),
        /** 国家/地区代码 BG。 */
        BG("Bulgaria"),
        /** 国家/地区代码 BH。 */
        BH("Bahrain"),
        /** 国家/地区代码 BI。 */
        BI("Burundi"),
        /** 国家/地区代码 BJ。 */
        BJ("Benin"),
        /** 国家/地区代码 BM。 */
        BM("Bermuda"),
        /** 国家/地区代码 BN。 */
        BN("Brunei"),
        /** 国家/地区代码 BO。 */
        BO("Bolivia"),
        /** 国家/地区代码 BR。 */
        BR("Brazil"),
        /** 国家/地区代码 BS。 */
        BS("Bahamas"),
        /** 国家/地区代码 BT。 */
        BT("Bhutan"),
        /** 国家/地区代码 BU。 */
        BU("Burma"),
        /** 国家/地区代码 BV。 */
        BV("Bouvet Island"),
        /** 国家/地区代码 BW。 */
        BW("Botswana"),
        /** 国家/地区代码 BY。 */
        BY("Belarus"),
        /** 国家/地区代码 BZ。 */
        BZ("Belize"),
        /** 国家/地区代码 CA。 */
        CA("Canada"),
        /** 国家/地区代码 CC。 */
        CC("Cocos (Keeling) Islands"),
        /** 国家/地区代码 CD。 */
        CD("Democratic Republic of the Congo (Kinshasa)"),
        /** 国家/地区代码 CF。 */
        CF("Central African Republic"),
        /** 国家/地区代码 CG。 */
        CG("Republic of the Congo (Brazzaville)"),
        /** 国家/地区代码 CH。 */
        CH("Switzerland"),
        /** 国家/地区代码 CI。 */
        CI("Côte d’Ivoire"),
        /** 国家/地区代码 CK。 */
        CK("Cook Islands"),
        /** 国家/地区代码 CL。 */
        CL("Chile"),
        /** 国家/地区代码 CM。 */
        CM("Cameroon"),
        /** 国家/地区代码 CN。 */
        CN("China"),
        /** 国家/地区代码 CO。 */
        CO("Colombia"),
        /** 国家/地区代码 CR。 */
        CR("Costa Rica"),
        /** 国家/地区代码 CS。 */
        CS("Serbia and Montenegro"),
        /** 国家/地区代码 CU。 */
        CU("Cuba"),
        /** 国家/地区代码 CV。 */
        CV("Cape Verde"),
        /** 国家/地区代码 CX。 */
        CX("Christmas Island"),
        /** 国家/地区代码 CY。 */
        CY("Cyprus"),
        /** 国家/地区代码 CZ。 */
        CZ("Czech Republic"),
        /** 国家/地区代码 DE。 */
        DE("Germany"),
        /** 国家/地区代码 DJ。 */
        DJ("Djibouti"),
        /** 国家/地区代码 DK。 */
        DK("Denmark"),
        /** 国家/地区代码 DM。 */
        DM("Dominica"),
        /** 国家/地区代码 DO。 */
        DO("Dominican Republic"),
        /** 国家/地区代码 DZ。 */
        DZ("Algeria"),
        /** 国家/地区代码 EC。 */
        EC("Ecuador"),
        /** 国家/地区代码 EE。 */
        EE("Estonia"),
        /** 国家/地区代码 EG。 */
        EG("Egypt"),
        /** 国家/地区代码 EH。 */
        EH("Western Sahara"),
        /** 国家/地区代码 ER。 */
        ER("Eritrea"),
        /** 国家/地区代码 ES。 */
        ES("Spain"),
        /** 国家/地区代码 ET。 */
        ET("Ethiopia"),
        /** 国家/地区代码 FI。 */
        FI("Finland"),
        /** 国家/地区代码 FJ。 */
        FJ("Fiji"),
        /** 国家/地区代码 FK。 */
        FK("Falkland Islands"),
        /** 国家/地区代码 FM。 */
        FM("Micronesia"),
        /** 国家/地区代码 FO。 */
        FO("Faroe Islands"),
        /** 国家/地区代码 FR。 */
        FR("France"),
        /** 国家/地区代码 GA。 */
        GA("Gabon"),
        /** 国家/地区代码 GB。 */
        GB("United Kingdom"),
        /** 国家/地区代码 GD。 */
        GD("Grenada"),
        /** 国家/地区代码 GE。 */
        GE("Georgia"),
        /** 国家/地区代码 GF。 */
        GF("French Guiana"),
        /** 国家/地区代码 GH。 */
        GH("Ghana"),
        /** 国家/地区代码 GI。 */
        GI("Gibraltar"),
        /** 国家/地区代码 GL。 */
        GL("Greenland"),
        /** 国家/地区代码 GM。 */
        GM("Gambia"),
        /** 国家/地区代码 GN。 */
        GN("Guinea"),
        /** 国家/地区代码 GP。 */
        GP("Guadeloupe"),
        /** 国家/地区代码 GQ。 */
        GQ("Equatorial Guinea"),
        /** 国家/地区代码 GR。 */
        GR("Greece"),
        /** 国家/地区代码 GS。 */
        GS("South Georgia & South Sandwich Islands"),
        /** 国家/地区代码 GT。 */
        GT("Guatemala"),
        /** 国家/地区代码 GU。 */
        GU("Guam"),
        /** 国家/地区代码 GW。 */
        GW("Guinea-Bissau"),
        /** 国家/地区代码 GY。 */
        GY("Guyana"),
        /** 国家/地区代码 HK。 */
        HK("Hong Kong SAR China"),
        /** 国家/地区代码 HM。 */
        HM("Heard & McDonald Islands"),
        /** 国家/地区代码 HN。 */
        HN("Honduras"),
        /** 国家/地区代码 HR。 */
        HR("Croatia"),
        /** 国家/地区代码 HT。 */
        HT("Haiti"),
        /** 国家/地区代码 HU。 */
        HU("Hungary"),
        /** 国家/地区代码 ID。 */
        ID("Indonesia"),
        /** 国家/地区代码 IE。 */
        IE("Ireland"),
        /** 国家/地区代码 IL。 */
        IL("Israel"),
        /** 国家/地区代码 IN。 */
        IN("India"),
        /** 国家/地区代码 IO。 */
        IO("British Indian Ocean Territory"),
        /** 国家/地区代码 IQ。 */
        IQ("Iraq"),
        /** 国家/地区代码 IR。 */
        IR("Iran"),
        /** 国家/地区代码 IS。 */
        IS("Iceland"),
        /** 国家/地区代码 IT。 */
        IT("Italy"),
        /** 国家/地区代码 JM。 */
        JM("Jamaica"),
        /** 国家/地区代码 JO。 */
        JO("Jordan"),
        /** 国家/地区代码 JP。 */
        JP("Japan"),
        /** 国家/地区代码 KE。 */
        KE("Kenya"),
        /** 国家/地区代码 KG。 */
        KG("Kyrgyzstan"),
        /** 国家/地区代码 KH。 */
        KH("Cambodia"),
        /** 国家/地区代码 KI。 */
        KI("Kiribati"),
        /** 国家/地区代码 KM。 */
        KM("Comoros"),
        /** 国家/地区代码 KN。 */
        KN("St. Kitts & Nevis"),
        /** 国家/地区代码 KP。 */
        KP("North Korea"),
        /** 国家/地区代码 KR。 */
        KR("South Korea"),
        /** 国家/地区代码 KW。 */
        KW("Kuwait"),
        /** 国家/地区代码 KY。 */
        KY("Cayman Islands"),
        /** 国家/地区代码 KZ。 */
        KZ("Kazakhstan"),
        /** 国家/地区代码 LA。 */
        LA("Laos"),
        /** 国家/地区代码 LB。 */
        LB("Lebanon"),
        /** 国家/地区代码 LC。 */
        LC("St. Lucia"),
        /** 国家/地区代码 LI。 */
        LI("Liechtenstein"),
        /** 国家/地区代码 LK。 */
        LK("Sri Lanka"),
        /** 国家/地区代码 LR。 */
        LR("Liberia"),
        /** 国家/地区代码 LS。 */
        LS("Lesotho"),
        /** 国家/地区代码 LT。 */
        LT("Lithuania"),
        /** 国家/地区代码 LU。 */
        LU("Luxembourg"),
        /** 国家/地区代码 LV。 */
        LV("Latvia"),
        /** 国家/地区代码 LY。 */
        LY("Libya"),
        /** 国家/地区代码 MA。 */
        MA("Morocco"),
        /** 国家/地区代码 MC。 */
        MC("Monaco"),
        /** 国家/地区代码 MD。 */
        MD("Moldova"),
        /** 国家/地区代码 ME。 */
        ME("Montenegro"),
        /** 国家/地区代码 MG。 */
        MG("Madagascar"),
        /** 国家/地区代码 MH。 */
        MH("Marshall Islands"),
        /** 国家/地区代码 MK。 */
        MK("Macedonia"),
        /** 国家/地区代码 ML。 */
        ML("Mali"),
        /** 国家/地区代码 MM。 */
        MM("Myanmar (Burma)"),
        /** 国家/地区代码 MN。 */
        MN("Mongolia"),
        /** 国家/地区代码 MO。 */
        MO("Macau SAR China"),
        /** 国家/地区代码 MP。 */
        MP("Northern Mariana Islands"),
        /** 国家/地区代码 MQ。 */
        MQ("Martinique"),
        /** 国家/地区代码 MR。 */
        MR("Mauritania"),
        /** 国家/地区代码 MS。 */
        MS("Montserrat"),
        /** 国家/地区代码 MT。 */
        MT("Malta"),
        /** 国家/地区代码 MU。 */
        MU("Mauritius"),
        /** 国家/地区代码 MV。 */
        MV("Maldives"),
        /** 国家/地区代码 MW。 */
        MW("Malawi"),
        /** 国家/地区代码 MX。 */
        MX("Mexico"),
        /** 国家/地区代码 MY。 */
        MY("Malaysia"),
        /** 国家/地区代码 MZ。 */
        MZ("Mozambique"),
        /** 国家/地区代码 NA。 */
        NA("Namibia"),
        /** 国家/地区代码 NC。 */
        NC("New Caledonia"),
        /** 国家/地区代码 NE。 */
        NE("Niger"),
        /** 国家/地区代码 NF。 */
        NF("Norfolk Island"),
        /** 国家/地区代码 NG。 */
        NG("Nigeria"),
        /** 国家/地区代码 NI。 */
        NI("Nicaragua"),
        /** 国家/地区代码 NL。 */
        NL("Netherlands"),
        /** 国家/地区代码 NO。 */
        NO("Norway"),
        /** 国家/地区代码 NP。 */
        NP("Nepal"),
        /** 国家/地区代码 NR。 */
        NR("Nauru"),
        /** 国家/地区代码 NU。 */
        NU("Niue"),
        /** 国家/地区代码 NZ。 */
        NZ("New Zealand"),
        /** 国家/地区代码 OM。 */
        OM("Oman"),
        /** 国家/地区代码 PA。 */
        PA("Panama"),
        /** 国家/地区代码 PE。 */
        PE("Peru"),
        /** 国家/地区代码 PF。 */
        PF("French Polynesia"),
        /** 国家/地区代码 PG。 */
        PG("Papua New Guinea"),
        /** 国家/地区代码 PH。 */
        PH("Philippines"),
        /** 国家/地区代码 PK。 */
        PK("Pakistan"),
        /** 国家/地区代码 PL。 */
        PL("Poland"),
        /** 国家/地区代码 PM。 */
        PM("St. Pierre & Miquelon"),
        /** 国家/地区代码 PN。 */
        PN("Pitcairn Islands"),
        /** 国家/地区代码 PR。 */
        PR("Puerto Rico"),
        /** 国家/地区代码 PS。 */
        PS("Palestinian Territories"),
        /** 国家/地区代码 PT。 */
        PT("Portugal"),
        /** 国家/地区代码 PW。 */
        PW("Palau"),
        /** 国家/地区代码 PY。 */
        PY("Paraguay"),
        /** 国家/地区代码 QA。 */
        QA("Qatar"),
        /** 国家/地区代码 RE。 */
        RE("Réunion"),
        /** 国家/地区代码 RO。 */
        RO("Romania"),
        /** 国家/地区代码 RS。 */
        RS("Serbia"),
        /** 国家/地区代码 RU。 */
        RU("Russia"),
        /** 国家/地区代码 RW。 */
        RW("Rwanda"),
        /** 国家/地区代码 SA。 */
        SA("Saudi Arabia"),
        /** 国家/地区代码 SB。 */
        SB("Solomon Islands"),
        /** 国家/地区代码 SC。 */
        SC("Seychelles"),
        /** 国家/地区代码 SD。 */
        SD("Sudan"),
        /** 国家/地区代码 SE。 */
        SE("Sweden"),
        /** 国家/地区代码 SG。 */
        SG("Singapore"),
        /** 国家/地区代码 SH。 */
        SH("St. Helena"),
        /** 国家/地区代码 SI。 */
        SI("Slovenia"),
        /** 国家/地区代码 SJ。 */
        SJ("Svalbard & Jan Mayen"),
        /** 国家/地区代码 SK。 */
        SK("Slovakia"),
        /** 国家/地区代码 SL。 */
        SL("Sierra Leone"),
        /** 国家/地区代码 SM。 */
        SM("San Marino"),
        /** 国家/地区代码 SN。 */
        SN("Senegal"),
        /** 国家/地区代码 SO。 */
        SO("Somalia"),
        /** 国家/地区代码 SR。 */
        SR("Suriname"),
        /** 国家/地区代码 SS。 */
        SS("South Sudan"),
        /** 国家/地区代码 ST。 */
        ST("São Tomé & Príncipe"),
        /** 国家/地区代码 SU。 */
        SU("Soviet Union"),
        /** 国家/地区代码 SV。 */
        SV("El Salvador"),
        /** 国家/地区代码 SY。 */
        SY("Syria"),
        /** 国家/地区代码 SZ。 */
        SZ("Eswatini (Swaziland)"),
        /** 国家/地区代码 TC。 */
        TC("Turks & Caicos Islands"),
        /** 国家/地区代码 TD。 */
        TD("Chad"),
        /** 国家/地区代码 TF。 */
        TF("French Southern Territories"),
        /** 国家/地区代码 TG。 */
        TG("Togo"),
        /** 国家/地区代码 TH。 */
        TH("Thailand"),
        /** 国家/地区代码 TJ。 */
        TJ("Tajikistan"),
        /** 国家/地区代码 TK。 */
        TK("Tokelau"),
        /** 国家/地区代码 TL。 */
        TL("Timor-Leste"),
        /** 国家/地区代码 TM。 */
        TM("Turkmenistan"),
        /** 国家/地区代码 TN。 */
        TN("Tunisia"),
        /** 国家/地区代码 TO。 */
        TO("Tonga"),
        /** 国家/地区代码 TP。 */
        TP("East Timor"),
        /** 国家/地区代码 TR。 */
        TR("Turkey"),
        /** 国家/地区代码 TT。 */
        TT("Trinidad & Tobago"),
        /** 国家/地区代码 TV。 */
        TV("Tuvalu"),
        /** 国家/地区代码 TW。 */
        TW("Taiwan"),
        /** 国家/地区代码 TZ。 */
        TZ("Tanzania"),
        /** 国家/地区代码 UA。 */
        UA("Ukraine"),
        /** 国家/地区代码 UG。 */
        UG("Uganda"),
        /** 国家/地区代码 UM。 */
        UM("U.S. Outlying Islands"),
        /** 国家/地区代码 US。 */
        US("United States"),
        /** 国家/地区代码 UY。 */
        UY("Uruguay"),
        /** 国家/地区代码 UZ。 */
        UZ("Uzbekistan"),
        /** 国家/地区代码 VA。 */
        VA("Vatican City"),
        /** 国家/地区代码 VC。 */
        VC("St. Vincent & Grenadines"),
        /** 国家/地区代码 VE。 */
        VE("Venezuela"),
        /** 国家/地区代码 VG。 */
        VG("British Virgin Islands"),
        /** 国家/地区代码 VI。 */
        VI("U.S. Virgin Islands"),
        /** 国家/地区代码 VN。 */
        VN("Vietnam"),
        /** 国家/地区代码 VU。 */
        VU("Vanuatu"),
        /** 国家/地区代码 WF。 */
        WF("Wallis & Futuna"),
        /** 国家/地区代码 WS。 */
        WS("Samoa"),
        /** 国家/地区代码 XC。 */
        XC("Czechoslovakia"),
        /** 国家/地区代码 XG。 */
        XG("East Germany"),
        /** 国家/地区代码 XI。 */
        XI("Northern Ireland"),
        /** 国家/地区代码 XK。 */
        XK("Kosovo"),
        /** 国家/地区代码 YE。 */
        YE("Yemen"),
        /** 国家/地区代码 YT。 */
        YT("Mayotte"),
        /** 国家/地区代码 YU。 */
        YU("Yugoslavia"),
        /** 国家/地区代码 ZA。 */
        ZA("South Africa"),
        /** 国家/地区代码 ZM。 */
        ZM("Zambia"),
        /** 国家/地区代码 ZR。 */
        ZR("Zaire"),
        /** 国家/地区代码 ZW。 */
        ZW("Zimbabwe");

        /**
         * 本地名称
        */
        private final String nativeName;

        Code(String nativeName) {
            this.nativeName = nativeName;
        }

        /**
         * NAT名称
         *
         * @return NAT名称的结果
         */
        public String nativeName() {
            return nativeName;
        }
    }
}

