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

    private String name;
    private String idNumber;
    private String address;
    private String gender;
    private String ethnicity;
    private String birthDate;
    private String issueAuthority;
    private String validPeriod;
    private float confidence;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIdNumber() { return idNumber; }
    public void setIdNumber(String idNumber) { this.idNumber = idNumber; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getEthnicity() { return ethnicity; }
    public void setEthnicity(String ethnicity) { this.ethnicity = ethnicity; }
    public String getBirthDate() { return birthDate; }
    public void setBirthDate(String birthDate) { this.birthDate = birthDate; }
    public String getIssueAuthority() { return issueAuthority; }
    public void setIssueAuthority(String issueAuthority) { this.issueAuthority = issueAuthority; }
    public String getValidPeriod() { return validPeriod; }
    public void setValidPeriod(String validPeriod) { this.validPeriod = validPeriod; }
    public float getConfidence() { return confidence; }
    public void setConfidence(float confidence) { this.confidence = confidence; }

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
