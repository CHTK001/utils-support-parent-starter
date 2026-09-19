package com.chua.deeplearning.support.idcard;

/**
 * 中国居民身份证识别结果
 *
 * <p>结构化解析 PaddleOCR 识别的身份证文字，提取姓名、身份证号、地址、性别、民族、出生日期、签发机关、有效期限。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public class CnIdCardResult {

    private String name; // 名称
    private String idNumber; // 标识数字
    private String address; // 地址
    private String gender; // gender
    private String ethnicity; // ethnicity
    private String birthDate; // birth日期
    private String issueAuthority; // issueauthority
    private String validPeriod; // valid周期
    private float confidence; // 信心

    /**
     * 获取名称。
     * @return 获取名称的结果
     */
    public String getName() { return name; }
    /**
     * 设置名称。
     * @param name 名称
     */
    public void setName(String name) { this.name = name; }
    /**
     * 获取标识数字。
     * @return 获取id数字的结果
     */
    public String getIdNumber() { return idNumber; }
    /**
     * 设置标识数字。
     * @param idNumber 标识数字
     */
    public void setIdNumber(String idNumber) { this.idNumber = idNumber; }
    /**
     * 获取地址。
     * @return 获取地址的结果
     */
    public String getAddress() { return address; }
    /**
     * 设置地址。
     * @param address 地址
     */
    public void setAddress(String address) { this.address = address; }
    /**
     * 获取gender。
     * @return 获取gender的结果
     */
    public String getGender() { return gender; }
    /**
     * 设置gender。
     * @param gender gender
     */
    public void setGender(String gender) { this.gender = gender; }
    /**
     * 获取ethnicity。
     * @return 获取ethnicity的结果
     */
    public String getEthnicity() { return ethnicity; }
    /**
     * 设置ethnicity。
     * @param ethnicity ethnicity
     */
    public void setEthnicity(String ethnicity) { this.ethnicity = ethnicity; }
    /**
     * 获取birth日期。
     * @return 获取birth日期的结果
     */
    public String getBirthDate() { return birthDate; }
    /**
     * 设置birth日期。
     * @param birthDate birth日期
     */
    public void setBirthDate(String birthDate) { this.birthDate = birthDate; }
    /**
     * 获取issueauthority。
     * @return 获取issueauthority的结果
     */
    public String getIssueAuthority() { return issueAuthority; }
    /**
     * 设置issueauthority。
     * @param issueAuthority issueauthority
     */
    public void setIssueAuthority(String issueAuthority) { this.issueAuthority = issueAuthority; }
    /**
     * 获取valid周期。
     * @return 获取valid周期的结果
     */
    public String getValidPeriod() { return validPeriod; }
    /**
     * 设置valid周期。
     * @param validPeriod valid周期
     */
    public void setValidPeriod(String validPeriod) { this.validPeriod = validPeriod; }
    /**
     * 获取信心。
     * @return 获取信心的结果
     */
    public float getConfidence() { return confidence; }
    /**
     * 设置信心。
     * @param confidence 信心
     */
    public void setConfidence(float confidence) { this.confidence = confidence; }

    /**
     * 是否valid。
     * @return 是否valid的结果
     */
    public boolean isValid() {
        return idNumber != null && idNumber.length() == 18;
    }

    @Override
    public String toString() {
        return "CnIdCardResult{" +
                "name='" + name + '\'' +
                ", idNumber='" + idNumber + '\'' +
                ", address='" + address + '\'' +
                ", gender='" + gender + '\'' +
                ", ethnicity='" + ethnicity + '\'' +
                ", birthDate='" + birthDate + '\'' +
                ", issueAuthority='" + issueAuthority + '\'' +
                ", validPeriod='" + validPeriod + '\'' +
                ", confidence=" + confidence +
                '}';
    }
}
