package com.chua.common.support.constant;

import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

import static com.chua.common.support.lang.date.enums.ZoneIdEnum.CTT;


/**
 *                         <br>
 *                               <br>
 * yyyy-MM-dd<br>
 * HH:mm:ss<br>
 * yyyy-MM-dd HH:mm:ss<br>
 * yyyy-MM-dd HH:mm:ss.SSS<br>
 * yyyy-MM-dd HH:mm:ss.SSSSSS<br>
 * yyyy-MM-dd HH:mm:ss.SSSSSSSSS<br>
 * yyyy-MM-dd'T'HH:mm:ss.SSSZ                                                         <br>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/12/31
 */
public interface DateFormatConstant {
    String[] MONTHS_OF_CHINESE = {"      ", "      ", "      ", "      ", "      ", "      ",
            "      ", "      ", "      ", "      ", "         ", "         "};
    // ==================================yyyy-MM-dd      Pattern==================================

    /**
     * yyyy-MM-dd            2020-05-23
     */
    String YYYY_MM_DD = "yyyy-MM-dd";

    /**
     * yyyy-M-d       0            2020-5-23
     */
    String YYYY_M_D = "yyyy-M-d";
    /**
     * yyyyMMdd             20200523
     */
    String YYYYMMDD = "yyyyMMdd";
    /**
     * yyyy/MM/dd             2020/05/23
     */
    String YYYY_MM_DD_EN = "yyyy/MM/dd";

    /**
     * yyyy/M/d       0             2020/5/23
     */
    String YYYY_M_D_EN = "yyyy/M/d";
    /**
     * yyyy   MM   dd               2020   05   23   
     */
    String YYYY_MM_DD_CN = "yyyy   MM   dd   ";

    /**
     * yyyy   M   d          0            2020   5   23   
     */
    String YYYY_M_D_CN = "yyyy   M   d   ";

    String ISO8601 = "yyyy-MM-dd'T'HH:mm:ss.SSS+08:00";

    /**
     * yyyy.MM.dd           2020.05.23
     */
    String YYYY_MM_DD_POINT = "yyyy.MM.dd";

    /**
     * yyyy.M.d       0           2020.5.23
     */
    String YYYY_M_D_POINT = "yyyy.M.d";
    /**
     * yy/MM/dd           20/05/23
     */
    String YY_MM_DD_EN = "yy/MM/dd";

    /**
     * yy/M/d           20/5/23
     */
    String YY_M_D_EN = "yy/M/d";
    /**
     * MM/dd/yy           05/23/20
     */
    String MM_DD_YY_EN = "MM/dd/yy";

    /**
     * M/d/yy           5/23/20
     */
    String M_D_YY_EN = "M/d/yy";
    /**
     * yyyy-MM-dd E           2020-05-23          
     */
    String YYYY_MM_DD_E = "yyyy-MM-dd E";

    /**
     * yy          2               20
     */
    String YY = "yy";

    /**
     * yyyy           2020
     */
    String YYYY = "yyyy";

    /**
     * yyyy-MM           2020-05
     */
    String YYYY_MM = "yyyy-MM";

    /**
     * yyyyMM           202005
     */
    String YYYYMM = "yyyyMM";

    /**
     * yyyy/MM           2020/05
     */
    String YYYY_MM_EN = "yyyy/MM";

    /**
     * yyyy   MM              2020   05   
     */
    String YYYY_MM_CN = "yyyy   MM   ";

    /**
     * yyyy   M              2020   5   
     */
    String YYYY_M_CN = "yyyy   M   ";
    /**
     * MM-dd           05-23
     */
    String MM_DD = "MM-dd";

    /**
     * MMdd           0523
     */
    String MMDD = "MMdd";

    /**
     * MM/dd           05/23
     */
    String MM_DD_EN = "MM/dd";

    /**
     * M/d       0           5/23
     */
    String M_D_EN = "M/d";

    /**
     * MM   dd              05   23   
     */
    String MM_DD_CN = "MM   dd   ";

    /**
     * M   d          0           5   23   
     */
    String M_D_CN = "M   d   ";


    // ==================================HH:mm:ss       Pattern==================================
    /**
     * yyyy-MM-dd HH:mm:ss          2020-05-23 17:06:30
     */
    String YYYY_MM_DD_HH_MM_SS = "yyyy-MM-dd HH:mm:ss";
    /**
     * yyyy-M-d H:m:s          2020-5-23 17:6:30
     */
    String YYYY_M_D_H_M_S = "yyyy-M-d H:m:s";
    /**
     * yyyyMMddHHmmss          20200523170630
     */
    String YYYYMMDDHHMMSS = "yyyyMMddHHmmss";
    /**
     * yyyy/MM/dd HH:mm:ss          2020/05/23 17:06:30
     */
    String YYYY_MM_DD_HH_MM_SS_EN = "yyyy/MM/dd HH:mm:ss";
    /**
     * yyyy/M/d H:m:s          2020/5/23 17:6:30
     */
    String YYYY_M_D_H_M_S_EN = "yyyy/M/d H:m:s";
    /**
     * yyyy   MM   dd    HH:mm:ss          2020   05   23    17:06:30
     */
    String YYYY_MM_DD_HH_MM_SS_CN = "yyyy   MM   dd    HH:mm:ss";
    /**
     * yyyy   MM   dd    HH   mm   ss             2020   05   23    17   06   30   
     */
    String YYYY_MM_DD_HH_MM_SS_CN_ALL = "yyyy   MM   dd    HH   mm   ss   ";
    /**
     * yyyy-MM-dd HH:mm          2020-05-23 17:06
     */
    String YYYY_MM_DD_HH_MM = "yyyy-MM-dd HH:mm";

    // ==================================HH:mm:ss.SSS       Pattern==================================
    /**
     * yyyy-M-d H:m          2020-5-23 17:6
     */
    String YYYY_M_D_H_M = "yyyy-M-d H:m";


    // ==================================HH:mm:ss.SSSSSS       Pattern==================================
    /**
     * yyyyMMddHHmm          202005231706
     */
    String YYYYMMDDHHMM = "yyyyMMddHHmm";


    // ==================================HH:mm:ss.SSSSSSSSS       Pattern==================================
    /**
     * yyyy/MM/dd HH:mm          2020/05/23 17:06
     */
    String YYYY_MM_DD_HH_MM_EN = "yyyy/MM/dd HH:mm";


    // ==================================yyyy-MM-dd HH:mm:ss       Pattern==================================
    /**
     * yyyy/M/d H:m          2020/5/23 17:6
     */
    String YYYY_M_D_H_M_EN = "yyyy/M/d H:m";
    /**
     * yyyy/M/d h:m a          2020/5/23 5:6       
     */
    String YYYY_M_D_H_M_A_EN = "yyyy/M/d h:m a";
    /**
     * MM-dd HH:mm          05-23 17:06
     */
    String MM_DD_HH_MM = "MM-dd HH:mm";
    /**
     * MM   dd    HH:mm          05   23    17:06
     */
    String MM_DD_HH_MM_CN = "MM   dd    HH:mm";
    /**
     * MM-dd HH:mm:ss          05-23 17:06:30
     */
    String MM_DD_HH_MM_SS = "MM-dd HH:mm:ss";
    /**
     * MM   dd    HH:mm:ss          05   23    17:06:30
     */
    String MM_DD_HH_MM_SS_CN = "MM   dd    HH:mm:ss";
    /**
     * yyyy   MM   dd    hh:mm:ss a          2020   05   23    05:06:30                            PM              Locale.ENGLISH
     */
    String YYYY_MM_DD_HH_MM_SS_A_CN = "yyyy   MM   dd    hh:mm:ss a";
    /**
     * yyyy   MM   dd    hh   mm   ss    a          2020   05   23    17   06   30                               PM              Locale.ENGLISH
     */
    String YYYY_MM_DD_HH_MM_SS_A_CN_ALL = "yyyy   MM   dd    hh   mm   ss    a";
    /**
     * yyyy-MM-dd HH:mm:ss.SSS          2020-05-23 17:06:30.272
     */
    String YYYY_MM_DD_HH_MM_SS_SSS = "yyyy-MM-dd HH:mm:ss.SSS";
    /**
     * yyyy-MM-dd HH:mm:ss,SSS          2020-05-23 17:06:30,272
     */
    String YYYY_MM_DD_HH_MM_SS_SSS_COMMA = "yyyy-MM-dd HH:mm:ss,SSS";
    /**
     * yyyyMMddHHmmssSSS          20200523170630272
     */
    String YYYYMMDDHHMMSSSSS = "yyyyMMddHHmmssSSS";
    /**
     * yyyy-M-d H:m:s.SSS          2020-5-23 17:6:30.272
     */
    String YYYY_M_D_H_M_S_SSS = "yyyy-M-d H:m:s.SSS";
    /**
     * yyyy/M/d H:m:s.SSS          2020/5/23 17:6:30.272
     */
    String YYYY_M_D_H_M_S_SSS_EN = "yyyy/M/d H:m:s.SSS";
    /**
     * yyyy-M-d H:m:s,SSS          2020-5-23 17:6:30,272
     */
    String YYYY_M_D_H_M_S_SSS_COMMA = "yyyy-M-d H:m:s,SSS";
    /**
     * yyyy-MM-dd HH:mm:ss.SSSSSS          2020-05-23 17:06:30.272150
     */
    String YYYY_MM_DD_HH_MM_SS_SSSSSS = "yyyy-MM-dd HH:mm:ss.SSSSSS";
    /**
     * yyyy-MM-dd HH:mm:ss.SSSSSSSSS          2020-05-23 17:06:30.272150620
     */
    String YYYY_MM_DD_HH_MM_SS_SSSSSSSSS = "yyyy-MM-dd HH:mm:ss.SSSSSSSSS";
    /**
     * yyyy-MM-dd'T'HH:mm:ssZ          2020-05-23T17:06:30+0800 2020-05-23T09:06:30+0000
     */
    String YYYY_MM_DD_T_HH_MM_SS_Z = "yyyy-MM-dd'T'HH:mm:ssZ";
    /**
     * yyyy-MM-dd'T'HH:mm:ssxxx          2020-05-23T17:06:30+08:00 2020-05-23T09:06:30+00:00
     */
    String YYYY_MM_DD_T_HH_MM_SS_XXX = "yyyy-MM-dd'T'HH:mm:ssxxx";
    /**
     * yyyy-MM-dd'T'HH:mm:ssXXX          2020-05-23T17:06:30+08:00 2020-05-23T09:06:30Z 0                   Z
     */
    String YYYY_MM_DD_T_HH_MM_SS_XXX_Z = "yyyy-MM-dd'T'HH:mm:ssXXX";


    // ==================================yyyy-MM-dd HH:mm:ss.SSS       Pattern==================================
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSZ          2020-05-23T17:06:30.272+0800 2020-05-23T09:06:30.272+0000
     */
    String YYYY_MM_DD_T_HH_MM_SS_SSS_Z = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSxxx          2020-05-23T17:06:30.272+08:00 2020-05-23T09:06:30.272+00:00
     */
    String YYYY_MM_DD_T_HH_MM_SS_SSS_XXX = "yyyy-MM-dd'T'HH:mm:ss.SSSxxx";
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSXXX          2020-05-23T17:06:30.272+08:00 2020-05-23T09:06:30.272Z 0                   Z
     */
    String YYYY_MM_DD_T_HH_MM_SS_SSS_XXX_Z = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ          2020-05-23T17:06:30.272150+0800 2020-05-23T09:06:30.272150+0000
     */
    String YYYY_MM_DD_T_HH_MM_SS_SSSSSS_Z = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ";
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx          2020-05-23T17:06:30.272150+08:00 2020-05-23T09:06:30.272150+00:00
     */
    String YYYY_MM_DD_T_HH_MM_SS_SSSSSS_XXX = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx";
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX          2020-05-23T17:06:30.272150+08:00 2020-05-23T09:06:30.272150Z 0                   Z
     */
    String YYYY_MM_DD_T_HH_MM_SS_SSSSSS_XXX_Z = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX";


    // ==================================yyyy-MM-dd HH:mm:ss.SSSSSS       Pattern==================================
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSZ          2020-05-23T17:06:30.272150620+0800 2020-05-23T09:06:30.272150620+0000
     */
    String YYYY_MM_DD_T_HH_MM_SS_SSSSSSSSS_Z = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSZ";


    // ==================================yyyy-MM-dd HH:mm:ss.SSSSSSSSS       Pattern==================================
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSxxx          2020-05-23T17:06:30.272150620+08:00 2020-05-23T09:06:30.272150620+00:00
     */
    String YYYY_MM_DD_T_HH_MM_SS_SSSSSSSSS_XXX = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSxxx";


    // ==================================Iso      Pattern        T==================================
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX          2020-05-23T17:06:30.272150620+08:00 2020-05-23T09:06:30.272150620Z 0                   Z
     */
    String YYYY_MM_DD_T_HH_MM_SS_SSSSSSSSS_XXX_Z = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX";
    /**
     * Date              EEE MMM dd HH:mm:ss zzz yyyy            Sat May 23 17:06:30 CST 2020
     */
    String EEE_MMM_DD_HH_MM_SS_ZZZ_YYYY = "EEE MMM dd HH:mm:ss zzz yyyy";
    /**
     *             ID Asia/Shanghai
     */
    String SHANGHAI_ZONE_ID = CTT.getZoneIdName();
    /**
     *               Asia/Shanghai
     */
    ZoneId SHANGHAI_ZONE = ZoneId.of(SHANGHAI_ZONE_ID);
    /**
     * yyyy-MM-dd'T'HH:mm:ssZ          2020-05-23T17:06:30+0800
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_Z_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_Z);
    /**
     * yyyy-MM-dd'T'HH:mm:ssxxx          2020-05-23T17:06:30+08:00
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_XXX_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_XXX);
    /**
     * yyyy-MM-dd'T'HH:mm:ssXXX          2020-05-23T17:06:30+08:00 0                   Z
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_XXX_Z_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_XXX_Z);
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSZ          2020-05-23T17:06:30.272+0800
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_SSS_Z_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_SSS_Z);
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSxxx          2020-05-23T17:06:30.272+08:00
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_SSS_XXX_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_SSS_XXX);
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSXXX          2020-05-23T17:06:30.272+08:00 0                   Z
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_SSS_XXX_Z_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_SSS_XXX_Z);
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ          2020-05-23T17:06:30.272150+0800 2020-05-23T09:06:30.272150+0000
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_SSSSSS_Z_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_SSSSSS_Z);
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx          2020-05-23T17:06:30.272150+08:00 2020-05-23T09:06:30.272150+00:00
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_SSSSSS_XXX_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_SSSSSS_XXX);


    // ==================================             Pattern==================================
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX          2020-05-23T17:06:30.272150+08:00 2020-05-23T09:06:30.272150Z 0                   Z
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_SSSSSS_XXX_Z_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_SSSSSS_XXX_Z);
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSZ          2020-05-23T17:06:30.272150620+0800 2020-05-23T09:06:30.272150620+0000
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_SSSSSSSSS_Z_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_SSSSSSSSS_Z);
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSxxx          2020-05-23T17:06:30.272150620+08:00 2020-05-23T09:06:30.272150620+00:00
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_SSSSSSSSS_XXX_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_SSSSSSSSS_XXX);
    /**
     * yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX          2020-05-23T17:06:30.272150620+08:00 2020-05-23T09:06:30.272150620Z 0                   Z
     */
    DateTimeFormatter YYYY_MM_DD_T_HH_MM_SS_SSSSSSSSS_XXX_Z_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_T_HH_MM_SS_SSSSSSSSS_XXX_Z);

    // ==================================yyyy-MM-dd      formatters==================================
    /**
     * such as '2011-12-03' or '2011-12-03+01:00'.
     */
    DateTimeFormatter ISO_DATE_FMT = DateTimeFormatter.ISO_DATE;
    /**
     * such as '2011-12-03T10:15:30','2011-12-03T10:15:30+01:00' or '2011-12-03T10:15:30+01:00[Europe/Paris]'.
     */
    DateTimeFormatter ISO_DATE_TIME_FMT = DateTimeFormatter.ISO_DATE_TIME;
    /**
     * such as '2011-12-03T10:15:30Z'.
     */
    DateTimeFormatter ISO_INSTANT_FMT = DateTimeFormatter.ISO_INSTANT;
    /**
     * such as '2011-12-03'.
     */
    DateTimeFormatter ISO_LOCAL_DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    /**
     * such as '2011-12-03T10:15:30'.
     */
    DateTimeFormatter ISO_LOCAL_DATE_TIME_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    /**
     * such as '10:15' or '10:15:30'.
     */
    DateTimeFormatter ISO_LOCAL_TIME_FMT = DateTimeFormatter.ISO_LOCAL_TIME;
    /**
     * such as '10:15', '10:15:30' or '10:15:30+01:00'.
     */
    DateTimeFormatter ISO_TIME_FMT = DateTimeFormatter.ISO_TIME;
    /**
     * such as '2012-W48-6'.
     */
    DateTimeFormatter ISO_WEEK_DATE_FMT = DateTimeFormatter.ISO_WEEK_DATE;
    /**
     * such as '2011-12-03T10:15:30+01:00[Europe/Paris]'.
     */
    DateTimeFormatter ISO_ZONED_DATE_TIME_FMT = DateTimeFormatter.ISO_ZONED_DATE_TIME;
    /**
     * such as '20111203'.
     */
    DateTimeFormatter BASIC_ISO_DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    /**
     * Date              EEE MMM dd HH:mm:ss zzz yyyy            Sat May 23 17:06:30 CST 2020
     */
    DateTimeFormatter EEE_MMM_DD_HH_MM_SS_ZZZ_YYYY_FMT = DateTimeFormatter.ofPattern(EEE_MMM_DD_HH_MM_SS_ZZZ_YYYY, Locale.ENGLISH);
    /**
     *                   
     */
    ZoneId ZONE = ZoneId.systemDefault();
    /**
     * yyyy-MM-dd            2020-05-23
     */
    DateTimeFormatter YYYY_MM_DD_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD).withZone(ZONE);
    /**
     * yyyy-M-d       0            2020-5-23
     */
    DateTimeFormatter YYYY_M_D_FMT = DateTimeFormatter.ofPattern(YYYY_M_D).withZone(ZONE);
    /**
     * yyyyMMdd             20200523
     */
    DateTimeFormatter YYYYMMDD_FMT = DateTimeFormatter.ofPattern(YYYYMMDD).withZone(ZONE);
    /**
     * yyyy/MM/dd             2020/05/23
     */
    DateTimeFormatter YYYY_MM_DD_EN_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_EN).withZone(ZONE);
    /**
     * yyyy/M/d       0             2020/5/23
     */
    DateTimeFormatter YYYY_M_D_EN_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_EN).withZone(ZONE);
    /**
     * yyyy   MM   dd               2020   05   23   
     */
    DateTimeFormatter YYYY_MM_DD_CN_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_CN).withZone(ZONE);
    /**
     * yyyy   M   d               2020   5   23   
     */
    DateTimeFormatter YYYY_M_D_CN_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_CN).withZone(ZONE);
    /**
     * yyyy.MM.dd           2020.05.23
     */
    DateTimeFormatter YYYY_MM_DD_POINT_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_POINT).withZone(ZONE);
    /**
     * yyyy.M.d       0           2020.5.23
     */
    DateTimeFormatter YYYY_M_D_POINT_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_POINT).withZone(ZONE);
    /**
     * yy/MM/dd       0           20/05/23
     */
    DateTimeFormatter YY_MM_DD_EN_FMT = DateTimeFormatter.ofPattern(YY_MM_DD_EN).withZone(ZONE);
    /**
     * yy/M/d           20/5/23
     */
    DateTimeFormatter YY_M_D_EN_FMT = DateTimeFormatter.ofPattern(YY_M_D_EN).withZone(ZONE);
    /**
     * MM/dd/yy       0           05/23/20
     */
    DateTimeFormatter MM_DD_YY_EN_FMT = DateTimeFormatter.ofPattern(MM_DD_YY_EN).withZone(ZONE);
    /**
     * M/d/yy           5/23/20
     */
    DateTimeFormatter M_D_YY_EN_FMT = DateTimeFormatter.ofPattern(M_D_YY_EN).withZone(ZONE);
    /**
     * yyyy-MM-dd E       0           2020-05-23          
     */
    DateTimeFormatter YYYY_MM_DD_E_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_E).withZone(ZONE);
    /**
     * yy          2               20
     */
    DateTimeFormatter YY_FMT = DateTimeFormatter.ofPattern(YY).withZone(ZONE);


    // ==================================HH:mm:ss       formatters==================================
    /**
     * yyyy           2020
     */
    DateTimeFormatter YYYY_FMT = DateTimeFormatter.ofPattern(YYYY).withZone(ZONE);
    /**
     * yyyy-MM           2020-05
     */
    DateTimeFormatter YYYY_MM_FMT = DateTimeFormatter.ofPattern(YYYY_MM).withZone(ZONE);
    /**
     * yyyyMM           202005
     */
    DateTimeFormatter YYYYMM_FMT = DateTimeFormatter.ofPattern(YYYYMM).withZone(ZONE);
    /**
     * yyyy/MM           2020/05
     */
    DateTimeFormatter YYYY_MM_EN_FMT = DateTimeFormatter.ofPattern(YYYY_MM_EN).withZone(ZONE);
    /**
     * yyyy   MM              2020   05   
     */
    DateTimeFormatter YYYY_MM_CN_FMT = DateTimeFormatter.ofPattern(YYYY_MM_CN).withZone(ZONE);
    /**
     * yyyy   M              2020   5   
     */
    DateTimeFormatter YYYY_M_CN_FMT = DateTimeFormatter.ofPattern(YYYY_M_CN).withZone(ZONE);
    /**
     * MM-dd           05-23
     */
    DateTimeFormatter MM_DD_FMT = DateTimeFormatter.ofPattern(MM_DD).withZone(ZONE);
    /**
     * MMdd           0523
     */
    DateTimeFormatter MMDD_FMT = DateTimeFormatter.ofPattern(MMDD).withZone(ZONE);
    /**
     * MM/dd           05/23
     */
    DateTimeFormatter MM_DD_EN_FMT = DateTimeFormatter.ofPattern(MM_DD_EN).withZone(ZONE);


    // ==================================HH:mm:ss.SSS       formatters==================================
    /**
     * M/d           5/23
     */
    DateTimeFormatter M_D_EN_FMT = DateTimeFormatter.ofPattern(M_D_EN).withZone(ZONE);

    // ==================================HH:mm:ss.SSSSSS       formatters==================================
    /**
     * MM   dd              05   23   
     */
    DateTimeFormatter MM_DD_CN_FMT = DateTimeFormatter.ofPattern(MM_DD_CN).withZone(ZONE);

    // ==================================HH:mm:ss.SSSSSSSSS       formatters==================================
    /**
     * M   d          0           5   23   
     */
    DateTimeFormatter M_D_CN_FMT = DateTimeFormatter.ofPattern(M_D_CN).withZone(ZONE);


    // ==================================yyyy-MM-dd HH:mm:ss       formatters==================================
    /**
     * yyyy-MM-dd HH:mm:ss          2020-05-23 17:06:30
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS).withZone(ZONE);
    /**
     * yyyy-MM-dd HH:mm:ss          2020-05-23 17:06:30
     */
    SimpleDateFormat YYYY_MM_DD_HH_MM_SS_SDF = new SimpleDateFormat(YYYY_MM_DD_HH_MM_SS);

    /**
     * yyyy-M-d H:m:s          2020-5-23 17:6:30
     */
    DateTimeFormatter YYYY_M_D_H_M_S_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_H_M_S).withZone(ZONE);

    /**
     * yyyyMMddHHmmss          20200523170630
     */
    DateTimeFormatter YYYYMMDDHHMMSS_FMT = DateTimeFormatter.ofPattern(YYYYMMDDHHMMSS).withZone(ZONE);

    /**
     * yyyy/MM/dd HH:mm:ss          2020/05/23 17:06:30
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_EN_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_EN).withZone(ZONE);

    /**
     * yyyy/M/d H:m:s          2020/5/23 17:6:30
     */
    DateTimeFormatter YYYY_M_D_H_M_S_EN_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_H_M_S_EN).withZone(ZONE);

    /**
     * yyyy   MM   dd    HH:mm:ss          2020   05   23    17:06:30
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_CN_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_CN).withZone(ZONE);

    /**
     * yyyy   MM   dd    HH   mm   ss             2020   05   23    17   06   30   
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_CN_ALL_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_CN_ALL).withZone(ZONE);

    /**
     * yyyy-MM-dd HH:mm          2020-05-23 17:06
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM).withZone(ZONE);

    /**
     * yyyy-M-d H:m          2020-5-23 17:6
     */
    DateTimeFormatter YYYY_M_D_H_M_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_H_M).withZone(ZONE);

    /**
     * yyyyMMddHHmm          202005231706
     */
    DateTimeFormatter YYYYMMDDHHMM_FMT = DateTimeFormatter.ofPattern(YYYYMMDDHHMM).withZone(ZONE);

    /**
     * yyyy/MM/dd HH:mm          2020/05/23 17:06
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_EN_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_EN).withZone(ZONE);

    /**
     * yyyy/M/d H:m          2020/5/23 17:6
     */
    DateTimeFormatter YYYY_M_D_H_M_EN_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_H_M_EN).withZone(ZONE);

    /**
     * yyyy/M/d h:m a          2020/5/23 5:6                    
     */
    DateTimeFormatter YYYY_M_D_H_M_A_EN_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_H_M_A_EN).withZone(ZONE);

    /**
     * yyyy/M/d h:m a          2020/5/23 5:6 PM  AM   PM
     */
    DateTimeFormatter YYYY_M_D_H_M_A_AM_PM_EN_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_H_M_A_EN, Locale.ENGLISH).withZone(ZONE);
    /**
     * MM-dd HH:mm          05-23 17:06
     */
    DateTimeFormatter MM_DD_HH_MM_FMT = DateTimeFormatter.ofPattern(MM_DD_HH_MM).withZone(ZONE);

    /**
     * MM   dd    HH:mm          05   23    17:06
     */
    DateTimeFormatter MM_DD_HH_MM_CN_FMT = DateTimeFormatter.ofPattern(MM_DD_HH_MM_CN).withZone(ZONE);

    /**
     * MM-dd HH:mm:ss          05-23 17:06:30
     */
    DateTimeFormatter MM_DD_HH_MM_SS_FMT = DateTimeFormatter.ofPattern(MM_DD_HH_MM_SS).withZone(ZONE);

    /**
     * MM   dd    HH:mm:ss          05   23    17:06:30
     */
    DateTimeFormatter MM_DD_HH_MM_SS_CN_FMT = DateTimeFormatter.ofPattern(MM_DD_HH_MM_SS_CN).withZone(ZONE);

    /**
     * yyyy   MM   dd    hh:mm:ss a          2020   05   23    05:06:30       
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_A_CN_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_A_CN).withZone(ZONE);

    /**
     * yyyy   MM   dd    hh:mm:ss a          2020   05   23    05:06:30 PM
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_A_AM_PM_CN_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_A_CN, Locale.ENGLISH).withZone(ZONE);

    /**
     * yyyy   MM   dd    hh   mm   ss    a          2020   05   23    17   06   30          
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_A_CN_ALL_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_A_CN_ALL).withZone(ZONE);

    /**
     * yyyy   MM   dd    hh   mm   ss    a          2020   05   23    17   06   30    PM
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_A_AM_PM_CN_ALL_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_A_CN_ALL, Locale.ENGLISH).withZone(ZONE);


    // ==================================yyyy-MM-dd HH:mm:ss.SSS       formatters==================================

    /**
     * yyyy-MM-dd HH:mm:ss.SSS          2020-05-23 17:06:30.272
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_SSS_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_SSS).withZone(ZONE);

    /**
     * yyyy-MM-dd HH:mm:ss,SSS          2020-05-23 17:06:30,272
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_SSS_COMMA_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_SSS_COMMA).withZone(ZONE);

    /**
     * yyyyMMddHHmmssSSS          20200523170630272 <br>
     * Jdk8        yyyyMMddHHmmssSSS bug                      :https://bugs.java.com/bugdatabase/view_bug.do?bug_id=JDK-8031085
     */
    DateTimeFormatter YYYYMMDDHHMMSSSSS_FMT = new DateTimeFormatterBuilder().appendPattern(YYYYMMDDHHMMSS).appendValue(ChronoField.MILLI_OF_SECOND, 3).toFormatter().withZone(ZONE);

    /**
     * yyyy-M-d H:m:s.SSS          2020-5-23 17:6:30.272
     */
    DateTimeFormatter YYYY_M_D_H_M_S_SSS_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_H_M_S_SSS).withZone(ZONE);

    /**
     * yyyy/M/d H:m:s.SSS          2020/5/23 17:6:30.272
     */
    DateTimeFormatter YYYY_M_D_H_M_S_SSS_EN_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_H_M_S_SSS_EN).withZone(ZONE);

    /**
     * yyyy-M-d H:m:s,SSS          2020-5-23 17:6:30,272
     */
    DateTimeFormatter YYYY_M_D_H_M_S_SSS_COMMA_FMT = DateTimeFormatter.ofPattern(YYYY_M_D_H_M_S_SSS_COMMA).withZone(ZONE);


    // ==================================yyyy-MM-dd HH:mm:ss.SSSSSS       formatters==================================
    /**
     * yyyy-MM-dd HH:mm:ss.SSSSSS          2020-05-23 17:06:30.272150
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_SSSSSS_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_SSSSSS).withZone(ZONE);


    // ==================================yyyy-MM-dd HH:mm:ss.SSSSSSSSS       formatters==================================
    /**
     * yyyy-MM-dd HH:mm:ss.SSSSSSSSS          2020-05-23 17:06:30.272150620
     */
    DateTimeFormatter YYYY_MM_DD_HH_MM_SS_SSSSSSSSS_FMT = DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_SSSSSSSSS).withZone(ZONE);


    // ==================================Iso      formatters        T                ==================================
    /**
     * HH:mm:ss           17:26:30
     */
    String HH_MM_SS = "HH:mm:ss";
    /**
     * HH:mm:ss           17:26:30
     */
    DateTimeFormatter HH_MM_SS_FMT = DateTimeFormatter.ofPattern(HH_MM_SS).withZone(ZONE);
    /**
     * H:m:s           17:6:30
     */
    String H_M_S = "H:m:s";
    /**
     * HHmmss           170630
     */
    String HHMMSS = "HHmmss";
    /**
     * HHmmss           170630
     */
    DateTimeFormatter HHMMSS_FMT = DateTimeFormatter.ofPattern(HHMMSS).withZone(ZONE);
    /**
     * HH   mm   ss              17   06   30   
     */
    String HH_MM_SS_CN = "HH   mm   ss   ";
    /**
     * HH:mm           17:06
     */
    String HH_MM = "HH:mm";
    /**
     * H:m           17:6
     */
    String H_M = "H:m";
    /**
     * HH   mm             17   06   
     */
    String HH_MM_CN = "HH   mm   ";
    /**
     * hh:mm a          05:06                           PM              Locale.ENGLISH
     */
    String HH_MM_A = "hh:mm a";
    /**
     * HH:mm:ss.SSS           17:26:30.272
     */
    String HH_MM_SS_SSS = "HH:mm:ss.SSS";
    /**
     * HH:mm:ss.SSSSSS           17:26:30.272150
     */
    String HH_MM_SS_SSSSSS = "HH:mm:ss.SSSSSS";
    // ==================================Iso      formatters        T    Jdk   ==================================
    /**
     * HH:mm:ss.SSSSSSSSS           17:26:30.272150620
     */
    String HH_MM_SS_SSSSSSSSS = "HH:mm:ss.SSSSSSSSS";
    /**
     * H:m:s           17:6:30
     */
    DateTimeFormatter H_M_S_FMT = DateTimeFormatter.ofPattern(H_M_S).withZone(ZONE);
    /**
     * HH   mm   ss              17   06   30   
     */
    DateTimeFormatter HH_MM_SS_CN_FMT = DateTimeFormatter.ofPattern(HH_MM_SS_CN).withZone(ZONE);
    /**
     * HH:mm           17:06
     */
    DateTimeFormatter HH_MM_FMT = DateTimeFormatter.ofPattern(HH_MM).withZone(ZONE);
    /**
     * H:m           17:6
     */
    DateTimeFormatter H_M_FMT = DateTimeFormatter.ofPattern(H_M).withZone(ZONE);
    /**
     * HH   mm             17   06   
     */
    DateTimeFormatter HH_MM_CN_FMT = DateTimeFormatter.ofPattern(HH_MM_CN).withZone(ZONE);
    /**
     * hh:mm a          05:06       
     */
    DateTimeFormatter HH_MM_A_FMT = DateTimeFormatter.ofPattern(HH_MM_A).withZone(ZONE);
    /**
     * hh:mm a          05:06 PM  AM PM
     */
    DateTimeFormatter HH_MM_A_AM_PM_FMT = DateTimeFormatter.ofPattern(HH_MM_A, Locale.ENGLISH).withZone(ZONE);
    /**
     * HH:mm:ss.SSS           17:26:30.272
     */
    DateTimeFormatter HH_MM_SS_SSS_FMT = DateTimeFormatter.ofPattern(HH_MM_SS_SSS).withZone(ZONE);
    /**
     * HH:mm:ss.SSSSSS           17:26:30.272150
     */
    DateTimeFormatter HH_MM_SS_SSSSSS_FMT = DateTimeFormatter.ofPattern(HH_MM_SS_SSSSSS).withZone(ZONE);


    // ==================================             formatters==================================
    /**
     * HH:mm:ss.SSSSSSSSS           17:26:30.272150620
     */
    DateTimeFormatter HH_MM_SS_SSSSSSSSS_FMT = DateTimeFormatter.ofPattern(HH_MM_SS_SSSSSSSSS).withZone(ZONE);


}


