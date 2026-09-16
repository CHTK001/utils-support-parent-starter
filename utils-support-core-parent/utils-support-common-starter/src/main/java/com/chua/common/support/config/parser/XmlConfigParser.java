package com.chua.common.support.config.parser;

import com.chua.common.support.config.source.MapPropertySource;
import com.chua.common.support.config.source.PropertiesMutiPropertySource;
import com.chua.common.support.config.source.PropertySource;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
* XML                
* <p>
*           XML                      
* </p>
* <h3>1.                   </h3>
* <pre>{@code
* <config>
*     <server>
*         <host>localhost</host>
*         <port>8080</port>
*     </server>
*     <database>
*         <url>jdbc:mysql://localhost:3306/test</url>
*         <username>root</username>
*     </database>
* </config>
* }</pre>
*                server.host=localhost, server.port=8080, database.url=...
*
* <h3>2.             </h3>
* <pre>{@code
* <config>
*     <property name="server.host" value="localhost"/>
*     <property name="server.port" value="8080"/>
*     <property name="app.name">MyApp</property>
* </config>
* }</pre>
*        value                                  
*                server.host=localhost, server.port=8080, app.name=MyApp
*
* <h3>3.                   </h3>
* <pre>{@code
* <config>
*     <server host="localhost" port="8080"/>
*     <database url="jdbc:mysql://localhost:3306/test">
*         <pool-size>10</pool-size>
*     </database>
* </config>
* }</pre>
*                server.host=localhost, server.port=8080, database.url=..., database.pool-size=10
*
* <h3>4.       /                      MutiPropertySource   </h3>
* <pre>{@code
* <configs>
*     <item>
*         <name>config1</name>
*         <value>1</value>
*     </item>
*     <item>
*         <name>config2</name>
*         <value>2</value>
*     </item>
* </configs>
* }</pre>
*                                                             
*
* @author CH
* @since 2023-09-05
 */
@Slf4j
@Spi({"xml"})
public class XmlConfigParser implements ConfigParser {

    @Override
    /** 解析 */
    public PropertySource parse(String urlPath, InputStream is) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(is);
            document.getDocumentElement().normalize();
            
            Element root = document.getDocumentElement();
            
            //                                                                      
            List<Map<String, Object>> listItems = parseAsList(root);
            if (listItems != null) {
                return new PropertiesMutiPropertySource(urlPath, listItems);
            }
            
            //                 property       
            Map<String, Object> propertyMap = parsePropertyFormat(root);
            if (propertyMap != null) {
                return new MapPropertySource(urlPath, propertyMap);
            }
            
            //                   
            Map<String, Object> map = parseElement(root);
            return new MapPropertySource(urlPath, map);
        } catch (Exception e) {
            log.error("       XML                   : {}", e.getMessage());
            return PropertySource.EMPTY;
        }
    }

    /**
    *                            
    *                                                       
    *
    * @param root          
    * @return                                            null
     */
    private List<Map<String, Object>> parseAsList(Element root) {
        NodeList children = root.getChildNodes();
        List<Element> elements = new ArrayList<>();
        String firstTagName = null;
        
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element elem = (Element) child;
                if (firstTagName == null) {
                    firstTagName = elem.getTagName();
                } else if (!firstTagName.equals(elem.getTagName())) {
                    //                                     
                    return null;
                }
                elements.add(elem);
            }
        }
        
        //          2                              
        if (elements.size() < 2) {
            return null;
        }
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (Element elem : elements) {
            result.add(parseElement(elem));
        }
        return result;
    }

    /**
    *                 property       
    * <p>
    *                      
    * <ul>
    *     <li>{@code <property name="key" value="value"/>}</li>
    *     <li>{@code <property name="key">value</property>}</li>
    *     <li>{@code <entry key="key" value="value"/>}</li>
    *     <li>{@code <entry key="key">value</entry>}</li>
    * </ul>
    *
    * @param root          
    * @return Map                property                 null
     */
    private Map<String, Object> parsePropertyFormat(Element root) {
        NodeList children = root.getChildNodes();
        Map<String, Object> result = new LinkedHashMap<>();
        boolean isPropertyFormat = false;
        
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element elem = (Element) child;
                String tagName = elem.getTagName();
                
                //        <property>     <entry>       
                if ("property".equalsIgnoreCase(tagName) || "entry".equalsIgnoreCase(tagName)) {
                    //        name/key
                    String name = elem.getAttribute("name");
                    if (name.isEmpty()) {
                        name = elem.getAttribute("key");
                    }
                    
                    if (!name.isEmpty()) {
                        isPropertyFormat = true;
                        
                        //              value                                           
                        String value = elem.getAttribute("value");
                        if (value.isEmpty()) {
                            //                                     
                            value = elem.getTextContent().trim();
                        }
                        result.put(name, value);
                    }
                }
            }
        }
        
        return isPropertyFormat ? result : null;
    }

    /**
    *              XML       
     */
    private Map<String, Object> parseElement(Element element) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        //             
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            result.put(attr.getNodeName(), attr.getNodeValue());
        }
        
        //                                              
        NodeList children = element.getChildNodes();
        Map<String, List<Object>> childMap = new LinkedHashMap<>();
        
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element childElement = (Element) child;
                String tagName = childElement.getTagName();
                
                Object value;
                if (hasOnlyTextContent(childElement)) {
                    value = childElement.getTextContent().trim();
                } else {
                    value = parseElement(childElement);
                }
                
                childMap.computeIfAbsent(tagName, k -> new ArrayList<>()).add(value);
            }
        }
        
        //                                                          
        for (Map.Entry<String, List<Object>> entry : childMap.entrySet()) {
            List<Object> values = entry.getValue();
            if (values.size() == 1) {
                result.put(entry.getKey(), values.getFirst());
            } else {
                result.put(entry.getKey(), values);
            }
        }
        
        return result;
    }

    /**
    *                                        
     */
    private boolean hasOnlyTextContent(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return false;
            }
        }
        return true;
    }

}