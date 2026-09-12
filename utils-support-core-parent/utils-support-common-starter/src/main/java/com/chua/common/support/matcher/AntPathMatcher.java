package com.chua.common.support.matcher;

import  com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.constant.CommonConstant;
import com.chua.common.support.utils.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.chua.common.support.constant.CommonConstant.*;


/**
* ant                      
*                      :
* '?' -                   
* '*' -                            
* '**' -                            
*
* @author CH
* @since 1.0
 */
public class AntPathMatcher implements PathMatcher {

    /**
    *                      : "/".
     */
    public static final String DEFAULT_PATH_SEPARATOR = "/";

    /**
    *                                                                   
     */
    private static final int CACHE_TURNOFF_THRESHOLD = 65536;

    /**
    *                                      {variable}                
     */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{[^/]+?}");



    /**
    *                                '*', '?', '{'
     */
    private static final char[] WILDCARD_CHARS = {'*', '?', '{'};

    /**
    *                
     */
    private String pathSeparator;

    /**
    *                            
     */
    private PathSeparatorPatternCache pathSeparatorPatternCache;

    /**
    *                                   true
     */
    private boolean caseSensitive = true;

    /**
    *                                                           false
     */
    private boolean trimTokens = false;

    /**
    *                                null                           
     */
    private volatile Boolean cachePatterns;

    /**
    *                                              
    *                                              
     */
    private final Map<String, String[]> tokenizedPatternCache = new ConcurrentReferenceHashMap<>(256);

    /**
    *                                               AntPathStringMatcher
    *                                              
     */
    private final Map<String, AntPathStringMatcher> stringMatcherCache = new ConcurrentReferenceHashMap<>(256);

    /**
    *                             "/"        AntPathMatcher
     */
    public AntPathMatcher() {
        this(DEFAULT_PATH_SEPARATOR);
    }

    /**
    *                                      AntPathMatcher
    *
    * @param pathSeparator                             {@code null}
    * @since 4.1
     */
    public AntPathMatcher(String pathSeparator) {
        if (null == pathSeparator) {
            pathSeparator = DEFAULT_PATH_SEPARATOR;
        }
        setPathSeparator(pathSeparator);
    }


    /**
    *                      
    *
    * @param pathSeparator             {@code null}                           {@link #DEFAULT_PATH_SEPARATOR}
    * @return this
     */
    public AntPathMatcher setPathSeparator(String pathSeparator) {
        if (null == pathSeparator) {
            pathSeparator = DEFAULT_PATH_SEPARATOR;
        }
        this.pathSeparator = pathSeparator;
        this.pathSeparatorPatternCache = new PathSeparatorPatternCache(this.pathSeparator);
        return this;
    }

    /**
    *                                        {@code true}
    *
    * @param caseSensitive                      
    * @return this
     */
    public AntPathMatcher setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
        return this;
    }

    /**
    *                                                             {@code false}
    *
    * @param trimTokens                                           
    * @return this
     */
    public AntPathMatcher setTrimTokens(boolean trimTokens) {
        this.trimTokens = trimTokens;
        return this;
    }

    /**
    *                                                 
    *        {@code true}                                        
    *        {@code false}                                  
    * <p>                                                                               65536                        
    *                                                                            
    *
    * @param cachePatterns                      
    * @return this
    * @see #getStringMatcher(String)
     */
    public AntPathMatcher setCachePatterns(boolean cachePatterns) {
        this.cachePatterns = cachePatterns;
        return this;
    }

    /**
    *                                                          
    *
    * @param path       
    * @return                   
     */
    @Override
    public boolean isPattern(String path) {
        if (path == null) {
            return false;
        }
        boolean uriVar = false;
        final int length = path.length();
        char c;
        for (int i = 0; i < length; i++) {
            c = path.charAt(i);
            //                
            if (c == WILDCARD_ASTERISK || c == WILDCARD_QUESTION) {
                return true;
            }
            if (c == SYMBOL_LEFT_BIG_PARANTHESES_CHAR) {
                uriVar = true;
                continue;
            }
            if (c == SYMBOL_RIGHT_BIG_PARANTHESES_CHAR && uriVar) {
                return true;
            }
        }
        return false;
    }

    /**
    *                                              
    *
    * @param pattern          
    * @param path          
    * @return             
     */
    @Override
    public boolean match(String pattern, String path) {
        return doMatch(pattern, path, true, null);
    }

    /**
    *                                              
    *
    * @param pattern          
    * @param path          
    * @return             
     */
    @Override
    public boolean matchStart(String pattern, String path) {
        return doMatch(pattern, path, false, null);
    }

    /**
    *                               {@code path}            {@code pattern}
    *
    * @param pattern                       
    * @param path                       
    * @param fullMatch                {@code true}                         {@code false}                     
    * @param uriTemplateVariables             
    * @return {@code true}                 {@code path}       , {@code false}                
     */
    protected boolean doMatch(String pattern, String path, boolean fullMatch, Map<String, String> uriTemplateVariables) {
        if (path == null || path.startsWith(this.pathSeparator) != pattern.startsWith(this.pathSeparator)) {
            return false;
        }

        final String[] pattDirs = tokenizePattern(pattern);
        if (fullMatch && this.caseSensitive && !isPotentialMatch(path, pattDirs)) {
            return false;
        }

        final String[] pathDirs = tokenizePath(path);
        int pattIdxStart = 0;
        int pattIdxEnd = pattDirs.length - 1;
        int pathIdxStart = 0;
        int pathIdxEnd = pathDirs.length - 1;

        //                 **                      
        while (pattIdxStart <= pattIdxEnd && pathIdxStart <= pathIdxEnd) {
            String pattDir = pattDirs[pattIdxStart];
            if (SYMBOL_ASTERISK_ANY.equals(pattDir)) {
                break;
            }
            if (notMatchStrings(pattDir, pathDirs[pathIdxStart], uriTemplateVariables)) {
                return false;
            }
            pattIdxStart++;
            pathIdxStart++;
        }

        if (pathIdxStart > pathIdxEnd) {
            //                                                  *     **             
            if (pattIdxStart > pattIdxEnd) {
                return (pattern.endsWith(this.pathSeparator) == path.endsWith(this.pathSeparator));
            }
            if (!fullMatch) {
                return true;
            }
            if (pattIdxStart == pattIdxEnd && SYMBOL_ASTERISK.equals(pattDirs[pattIdxStart]) && path.endsWith(this.pathSeparator)) {
                return true;
            }
            for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
                if (!SYMBOL_ASTERISK_ANY.equals(pattDirs[i])) {
                    return false;
                }
            }
            return true;
        } else if (pattIdxStart > pattIdxEnd) {
            //                                                    
            return false;
        } else if (!fullMatch && SYMBOL_ASTERISK_ANY.equals(pattDirs[pattIdxStart])) {
            //                    SYMBOL_ASTERISK_ANY                                     
            return true;
        }

        //                       **
        while (pattIdxStart <= pattIdxEnd && pathIdxStart <= pathIdxEnd) {
            String pattDir = pattDirs[pattIdxEnd];
            if (SYMBOL_ASTERISK_ANY.equals(pattDir)) {
                break;
            }
            if (notMatchStrings(pattDir, pathDirs[pathIdxEnd], uriTemplateVariables)) {
                return false;
            }
            pattIdxEnd--;
            pathIdxEnd--;
        }
        if (pathIdxStart > pathIdxEnd) {
            //                   
            for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
                if (!SYMBOL_ASTERISK_ANY.equals(pattDirs[i])) {
                    return false;
                }
            }
            return true;
        }

        while (pattIdxStart != pattIdxEnd && pathIdxStart <= pathIdxEnd) {
            int patIdxTmp = -1;
            for (int i = pattIdxStart + 1; i <= pattIdxEnd; i++) {
                if (SYMBOL_ASTERISK_ANY.equals(pattDirs[i])) {
                    patIdxTmp = i;
                    break;
                }
            }
            if (patIdxTmp == pattIdxStart + 1) {
                // '**/**'                      
                pattIdxStart++;
                continue;
            }
            //     padIdxStart     padIdxTmp                          strIdxStart     strIdxEnd           str          
            int patLength = (patIdxTmp - pattIdxStart - 1);
            int strLength = (pathIdxEnd - pathIdxStart + 1);
            int foundIdx = -1;

            strLoop:
            for (int i = 0; i <= strLength - patLength; i++) {
                for (int j = 0; j < patLength; j++) {
                    String subPat = pattDirs[pattIdxStart + j + 1];
                    String subStr = pathDirs[pathIdxStart + i + j];
                    if (notMatchStrings(subPat, subStr, uriTemplateVariables)) {
                        continue strLoop;
                    }
                }
                foundIdx = pathIdxStart + i;
                break;
            }

            if (foundIdx == -1) {
                return false;
            }

            pattIdxStart = patIdxTmp;
            pathIdxStart = foundIdx + patLength;
        }

        for (int i = pattIdxStart; i <= pattIdxEnd; i++) {
            if (!SYMBOL_ASTERISK_ANY.equals(pattDirs[i])) {
                return false;
            }
        }

        return true;
    }

    /**
    *                                                    
    *
    * @param path           
    * @param pattDirs                   
    * @return                   
     */
    private boolean isPotentialMatch(String path, String[] pattDirs) {
        if (!this.trimTokens) {
            int pos = 0;
            for (String pattDir : pattDirs) {
                int skipped = skipSeparator(path, pos, this.pathSeparator);
                pos += skipped;
                skipped = skipSegment(path, pos, pattDir);
                if (skipped < pattDir.length()) {
                    return (skipped > 0 || (pattDir.length() > 0 && isWildcardChar(pattDir.charAt(0))));
                }
                pos += skipped;
            }
        }
        return true;
    }

    /**
    *                                  
    *
    * @param path         
    * @param pos                
    * @param prefix       
    * @return                   
     */
    private int skipSegment(String path, int pos, String prefix) {
        int skipped = 0;
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (isWildcardChar(c)) {
                return skipped;
            }
            int currPos = pos + skipped;
            if (currPos >= path.length()) {
                return 0;
            }
            if (c == path.charAt(currPos)) {
                skipped++;
            }
        }
        return skipped;
    }

    /**
    *                            
    *
    * @param path            
    * @param pos                   
    * @param separator          
    * @return                   
     */
    private int skipSeparator(String path, int pos, String separator) {
        int skipped = 0;
        while (path.startsWith(separator, pos + skipped)) {
            skipped += separator.length();
        }
        return skipped;
    }

    /**
    *                               
    *
    * @param c       
    * @return                   
     */
    private boolean isWildcardChar(char c) {
        for (char candidate : WILDCARD_CHARS) {
            if (c == candidate) {
                return true;
            }
        }
        return false;
    }

    /**
    *                                                             
    * <p>       {@link #setCachePatterns}                         
    * {@link #tokenizePath(String)}                               
    *
    * @param pattern                   
    * @return                         
     */
    protected String[] tokenizePattern(String pattern) {
        String[] tokenized = null;
        Boolean cachePatterns = this.cachePatterns;
        if (cachePatterns == null || cachePatterns) {
            tokenized = this.tokenizedPatternCache.get(pattern);
        }
        if (tokenized == null) {
            tokenized = tokenizePath(pattern);
            if (cachePatterns == null && this.tokenizedPatternCache.size() >= CACHE_TURNOFF_THRESHOLD) {
                //                                              
                //                                     ...
                //                                                                            
                deactivatePatternCache();
                return tokenized;
            }
            if (cachePatterns == null || cachePatterns) {
                this.tokenizedPatternCache.put(pattern, tokenized);
            }
        }
        return tokenized;
    }

    /**
    *                   
     */
    private void deactivatePatternCache() {
        this.cachePatterns = false;
        this.tokenizedPatternCache.clear();
        this.stringMatcherCache.clear();
    }

    /**
    *                                                       
    *
    * @param path                   
    * @return                         
     */
    protected String[] tokenizePath(String path) {
        return StringUtils.splitToArray(path, this.pathSeparator, 0, this.trimTokens, true);
    }

    /**
    *                                        
    *
    * @param pattern                                {@code null}   
    * @param str                                                   {@code null}   
    * @return                                         {@code true}                {@code false}
     */
    private boolean notMatchStrings(String pattern, String str, Map<String, String> uriTemplateVariables) {
        return !getStringMatcher(pattern).matchStrings(str, uriTemplateVariables);
    }

    /**
    *                                   {@link AntPathStringMatcher}   
    * <p>                      AntPathMatcher                
    * (       {@link #setCachePatterns})                                              AntPathStringMatcher          
    * <p>                                                          65536         
    *                                                                                                       
    * <p>                                                         
    *
    * @param pattern                                {@code null}   
    * @return  AntPathStringMatcher             {@code null}   
    * @see #setCachePatterns
     */
    protected AntPathStringMatcher getStringMatcher(String pattern) {
        AntPathStringMatcher matcher = null;
        Boolean cachePatterns = this.cachePatterns;
        if (cachePatterns == null || cachePatterns) {
            matcher = this.stringMatcherCache.get(pattern);
        }
        if (matcher == null) {
            matcher = new AntPathStringMatcher(pattern, this.caseSensitive);
            if (cachePatterns == null && this.stringMatcherCache.size() >= CACHE_TURNOFF_THRESHOLD) {
                //                                              
                //                                     ...
                //                                                                            
                deactivatePatternCache();
                return matcher;
            }
            if (cachePatterns == null || cachePatterns) {
                this.stringMatcherCache.put(pattern, matcher);
            }
        }
        return matcher;
    }

    /**
    *                                                                   
    * <p>         
    * <ul>
    * <li>'{@code /docs/cvs/commit.html}'     '{@code /docs/cvs/commit.html} &rarr; ''</li>
    * <li>'{@code /docs/*}'     '{@code /docs/cvs/commit} &rarr; '{@code cvs/commit}'</li>
    * <li>'{@code /docs/cvs/*.html}'     '{@code /docs/cvs/commit.html} &rarr; '{@code commit.html}'</li>
    * <li>'{@code /docs/**}'     '{@code /docs/cvs/commit} &rarr; '{@code cvs/commit}'</li>
    * <li>'{@code /docs/**\/*.html}'     '{@code /docs/cvs/commit.html} &rarr; '{@code cvs/commit.html}'</li>
    * <li>'{@code /*.html}'     '{@code /docs/cvs/commit.html} &rarr; '{@code docs/cvs/commit.html}'</li>
    * <li>'{@code *.html}'     '{@code /docs/cvs/commit.html} &rarr; '{@code /docs/cvs/commit.html}'</li>
    * <li>'{@code *}'     '{@code /docs/cvs/commit.html} &rarr; '{@code /docs/cvs/commit.html}'</li>
    * </ul>
    * <p>       {@link #match}     '{@code pattern}'     '{@code path}'        {@code true}   
    *    <strong>   </strong>                        
    *
    * @param pattern          
    * @param path          
    * @return                            
     */
    public String extractPathWithinPattern(String pattern, String path) {
        String[] patternParts = tokenizePath(pattern);
        String[] pathParts = tokenizePath(path);
        StringBuilder builder = new StringBuilder();
        boolean pathStarted = false;

        for (int segment = 0; segment < patternParts.length; segment++) {
            String patternPart = patternParts[segment];
            if (patternPart.indexOf('*') > -1 || patternPart.indexOf('?') > -1) {
                for (; segment < pathParts.length; segment++) {
                    if (pathStarted || (segment == 0 && !pattern.startsWith(this.pathSeparator))) {
                        builder.append(this.pathSeparator);
                    }
                    builder.append(pathParts[segment]);
                    pathStarted = true;
                }
            }
        }

        return builder.toString();
    }

    /**
    *        URI             
    *
    * @param pattern       
    * @param path          
    * @return URI                   
     */
    public Map<String, String> extractUriTemplateVariables(String pattern, String path) {
        Map<String, String> variables = new LinkedHashMap<>();
        boolean result = doMatch(pattern, path, true, variables);
        if (!result) {
            throw new IllegalStateException("Pattern \"" + pattern + "\" is not a match for \"" + path + "\"");
        }
        return variables;
    }

    /**
    *                                           
    * <p>                                             
    *                                                       {@code *.html}      
    *                                                                         
    *           {@code IllegalArgumentException}   
    * <p>      </p>
    * <table border="1" summary="">
    * <tr><th>       1</th><th>       2</th><th>      </th></tr>
    * <tr><td>{@code null}</td><td>{@code null}</td><td>&nbsp;</td></tr>
    * <tr><td>/hotels</td><td>{@code null}</td><td>/hotels</td></tr>
    * <tr><td>{@code null}</td><td>/hotels</td><td>/hotels</td></tr>
    * <tr><td>/hotels</td><td>/bookings</td><td>/hotels/bookings</td></tr>
    * <tr><td>/hotels</td><td>bookings</td><td>/hotels/bookings</td></tr>
    * <tr><td>/hotels/*</td><td>/bookings</td><td>/hotels/bookings</td></tr>
    * <tr><td>/hotels/&#42;&#42;</td><td>/bookings</td><td>/hotels/&#42;&#42;/bookings</td></tr>
    * <tr><td>/hotels</td><td>{hotel}</td><td>/hotels/{hotel}</td></tr>
    * <tr><td>/hotels/*</td><td>{hotel}</td><td>/hotels/{hotel}</td></tr>
    * <tr><td>/hotels/&#42;&#42;</td><td>{hotel}</td><td>/hotels/&#42;&#42;/{hotel}</td></tr>
    * <tr><td>/*.html</td><td>/hotels.html</td><td>/hotels.html</td></tr>
    * <tr><td>/*.html</td><td>/hotels</td><td>/hotels.html</td></tr>
    * <tr><td>/*.html</td><td>/*.txt</td><td>{@code IllegalArgumentException}</td></tr>
    * </table>
    *
    * @param pattern1                
    * @param pattern2                
    * @return                      
    * @throws IllegalArgumentException                               
     */
    public String combine(String pattern1, String pattern2) {
        if (StringUtils.isEmpty(pattern1) && StringUtils.isEmpty(pattern2)) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        if (StringUtils.isEmpty(pattern1)) {
            return pattern2;
        }
        if (StringUtils.isEmpty(pattern2)) {
            return pattern1;
        }

        boolean pattern1ContainsUriVar = (pattern1.indexOf('{') != -1);
        if (!pattern1.equals(pattern2) && !pattern1ContainsUriVar && match(pattern1, pattern2)) {
            // /* + /hotel -> /hotel ; "/*.*" + "/*.html" -> /*.html
            // However /user + /user -> /usr/user ; /{foo} + /bar -> /{foo}/bar
            return pattern2;
        }

        // /hotels/* + /booking -> /hotels/booking
        // /hotels/* + booking -> /hotels/booking
        if (pattern1.endsWith(this.pathSeparatorPatternCache.getEndsOnWildCard())) {
            return concat(pattern1.substring(0, pattern1.length() - 2), pattern2);
        }

        // /hotels/** + /booking -> /hotels/**/booking
        // /hotels/** + booking -> /hotels/**/booking
        if (pattern1.endsWith(this.pathSeparatorPatternCache.getEndsOnDoubleWildCard())) {
            return concat(pattern1, pattern2);
        }

        int starDotPos1 = pattern1.indexOf("*.");
        if (pattern1ContainsUriVar || starDotPos1 == -1 || ".".equals(this.pathSeparator)) {
            //                            
            return concat(pattern1, pattern2);
        }

        String ext1 = pattern1.substring(starDotPos1 + 1);
        int dotPos2 = pattern2.indexOf('.');
        String file2 = (dotPos2 == -1 ? pattern2 : pattern2.substring(0, dotPos2));
        String ext2 = (dotPos2 == -1 ? "" : pattern2.substring(dotPos2));
        boolean ext1All = (".*".equals(ext1) || ext1.isEmpty());
        boolean ext2All = (".*".equals(ext2) || ext2.isEmpty());
        if (!ext1All && !ext2All) {
            throw new IllegalArgumentException("Cannot combine patterns: " + pattern1 + " vs " + pattern2);
        }
        String ext = (ext1All ? ext2 : ext1);
        return file2 + ext;
    }

    /**
    *                   
    *
    * @param path1       1
    * @param path2       2
    * @return                   
     */
    private String concat(String path1, String path2) {
        boolean path1EndsWithSeparator = path1.endsWith(this.pathSeparator);
        boolean path2StartsWithSeparator = path2.startsWith(this.pathSeparator);

        if (path1EndsWithSeparator && path2StartsWithSeparator) {
            return path1 + path2.substring(1);
        } else if (path1EndsWithSeparator || path2StartsWithSeparator) {
            return path1 + path2;
        } else {
            return path1 + this.pathSeparator + path2;
        }
    }

    /**
    *                                                              {@link Comparator}   
    * <p>    {@code Comparator}     {@linkplain List#sort(Comparator)       }
    *                                         URI                            
    *                                                                                     
    *                                        
    * <ol>
    * <li>{@code /hotels/new}</li>
    * <li>{@code /hotels/{hotel}}</li>
    * <li>{@code /hotels/*}</li>
    * </ol>
    * <p>                                                                                    
    *     {@code /hotels/2}              {@code /hotels/2}           {@code /hotels/1}          
    *
    * @param path                            
    * @return                                           
     */
    public Comparator<String> getPatternComparator(String path) {
        return new AntPatternComparator(path);
    }


    /**
    *        {@link Pattern}                                        
    * <p>                                 '*'                               '?'                                  
    * '{'     '}'        URI                       <tt>/users/{user}</tt>   
     */
    protected static class AntPathStringMatcher {

        /**
        *                                      ?, *, {variable}             
         */
        private static final Pattern GLOB_PATTERN = Pattern.compile("\\?|\\*|\\{((?:\\{[^/]+?}|[^/{}]|\\\\[{}])+?)}");

        /**
        *                                              
         */
        private static final String DEFAULT_VARIABLE_PATTERN = "((?s).*)";

        /**
        *                      
         */
        private final String rawPattern;

        /**
        *                      
         */
        private final boolean caseSensitive;

        /**
        *                                        
         */
        private final boolean exactMatch;

        /**
        *                                  
         */
        private final Pattern pattern;

        /**
        *                   
         */
        private final List<String> variableNames = new ArrayList<>();

        /**
        *        AntPathStringMatcher
        *
        * @param pattern                      
        * @param caseSensitive                      
         */
        public AntPathStringMatcher(String pattern, boolean caseSensitive) {
            this.rawPattern = pattern;
            this.caseSensitive = caseSensitive;
            StringBuilder patternBuilder = new StringBuilder();
            Matcher matcher = GLOB_PATTERN.matcher(pattern);
            int end = 0;
            while (matcher.find()) {
                patternBuilder.append(quote(pattern, end, matcher.start()));
                String match = matcher.group();
                if ("?".equals(match)) {
                    patternBuilder.append('.');
                } else if ("*".equals(match)) {
                    patternBuilder.append(".*");
                } else if (match.startsWith("{") && match.endsWith("}")) {
                    int colonIdx = match.indexOf(':');
                    if (colonIdx == -1) {
                        patternBuilder.append(DEFAULT_VARIABLE_PATTERN);
                        this.variableNames.add(matcher.group(1));
                    } else {
                        String variablePattern = match.substring(colonIdx + 1, match.length() - 1);
                        patternBuilder.append('(');
                        patternBuilder.append(variablePattern);
                        patternBuilder.append(')');
                        String variableName = match.substring(1, colonIdx);
                        this.variableNames.add(variableName);
                    }
                }
                end = matcher.end();
            }
            //                                                    
            if (end == 0) {
                this.exactMatch = true;
                this.pattern = null;
            } else {
                this.exactMatch = false;
                patternBuilder.append(quote(pattern, end, pattern.length()));
                this.pattern = (this.caseSensitive ? Pattern.compile(patternBuilder.toString()) :
                        Pattern.compile(patternBuilder.toString(), Pattern.CASE_INSENSITIVE));
            }
        }

        /**
        *                                           
        *
        * @param s              
        * @param start             
        * @param end               
        * @return                      
         */
        private String quote(String s, int start, int end) {
            if (start == end) {
                return "";
            }
            return Pattern.quote(s.substring(start, end));
        }

        /**
        *                                                       
        *
        * @param str                           
        * @param uriTemplateVariables URI             
        * @return                                         {@code true}                {@code false}
         */
        public boolean matchStrings(String str, Map<String, String> uriTemplateVariables) {
            if (this.exactMatch) {
                return this.caseSensitive ? this.rawPattern.equals(str) : this.rawPattern.equalsIgnoreCase(str);
            } else if (this.pattern != null) {
                Matcher matcher = this.pattern.matcher(str);
                if (matcher.matches()) {
                    if (uriTemplateVariables != null) {
                        if (this.variableNames.size() != matcher.groupCount()) {
                            throw new IllegalArgumentException("The number of capturing groups in the pattern segment " +
                                    this.pattern + " does not match the number of URI template variables it defines, " +
                                    "which can occur if capturing groups are used in a URI template regex. " +
                                    "Use non-capturing groups instead.");
                        }
                        for (int i = 1; i <= matcher.groupCount(); i++) {
                            String name = this.variableNames.get(i - 1);
                            if (name.startsWith("*")) {
                                throw new IllegalArgumentException("Capturing patterns (" + name + ") are not " +
                                        "supported by the AntPathMatcher. Use the PathPatternParser instead.");
                            }
                            String value = matcher.group(i);
                            uriTemplateVariables.put(name, value);
                        }
                    }
                    return true;
                }
            }
            return false;
        }

    }


    /**
    * {@link #getPatternComparator(String)}                 {@link Comparator}          
    * <p>               "      "                                 
    * <ul>
    * <li>          null                                   "/**"   </li>
    * <li>                                 </li>
    * <li>                                     SYMBOL_ASTERISK_ANY          </li>
    * <li>                                     "*" </li>
    * <li>                                     "{foo}" </li>
    * <li>                              </li>
    * </ul>
     */
    protected static class AntPatternComparator implements Comparator<String> {

        /**
        *                      
         */
        private final String path;

        /**
        *        AntPatternComparator
        *
        * @param path                      
         */
        public AntPatternComparator(String path) {
            this.path = path;
        }

        /**
        *                                                                
        *                                  
        *
        * @param pattern1          1
        * @param pattern2          2
        * @return                                      pattern1     pattern2                                        
         */
        @Override
        public int compare(String pattern1, String pattern2) {
            PatternInfo info1 = new PatternInfo(pattern1);
            PatternInfo info2 = new PatternInfo(pattern2);

            if (info1.isLeastSpecific() && info2.isLeastSpecific()) {
                return 0;
            } else if (info1.isLeastSpecific()) {
                return 1;
            } else if (info2.isLeastSpecific()) {
                return -1;
            }

            boolean pattern1EqualsPath = pattern1.equals(this.path);
            boolean pattern2EqualsPath = pattern2.equals(this.path);
            if (pattern1EqualsPath && pattern2EqualsPath) {
                return 0;
            } else if (pattern1EqualsPath) {
                return -1;
            } else if (pattern2EqualsPath) {
                return 1;
            }

            if (info1.isPrefixPattern() && info2.isPrefixPattern()) {
                return info2.getLength() - info1.getLength();
            } else if (info1.isPrefixPattern() && info2.getDoubleWildcards() == 0) {
                return 1;
            } else if (info2.isPrefixPattern() && info1.getDoubleWildcards() == 0) {
                return -1;
            }

            if (info1.getTotalCount() != info2.getTotalCount()) {
                return info1.getTotalCount() - info2.getTotalCount();
            }

            if (info1.getLength() != info2.getLength()) {
                return info2.getLength() - info1.getLength();
            }

            if (info1.getSingleWildcards() < info2.getSingleWildcards()) {
                return -1;
            } else if (info2.getSingleWildcards() < info1.getSingleWildcards()) {
                return 1;
            }

            if (info1.getUriVars() < info2.getUriVars()) {
                return -1;
            } else if (info2.getUriVars() < info1.getUriVars()) {
                return 1;
            }

            return 0;
        }


        /**
        *                                               "*"   SYMBOL_ASTERISK_ANY     "{"                               
         */
        private static class PatternInfo {

            /**
            *                
             */
            private final String pattern;
            
            /**
            * URI             
             */
            private int uriVars;
            
            /**
            *             (*)      
             */
            private int singleWildcards;
            
            /**
            *             (**)      
             */
            private int doubleWildcards;
            
            /**
            *                            (/**)
             */
            private boolean catchAllPattern;
            
            /**
            *                      (   /**               /**      )
             */
            private boolean prefixPattern;
            
            /**
            *             
             */
            private Integer length;

            /**
            *        PatternInfo
            *
            * @param pattern                
             */
            public PatternInfo(String pattern) {
                this.pattern = pattern;
                if (this.pattern != null) {
                    initCounters();
                    this.catchAllPattern = "/**".equals(this.pattern);
                    this.prefixPattern = !this.catchAllPattern && this.pattern.endsWith("/**");
                }
                if (this.uriVars == 0) {
                    this.length = (this.pattern != null ? this.pattern.length() : 0);
                }
            }

            /**
            *                   
             */
            protected void initCounters() {
                int pos = 0;
                if (this.pattern != null) {
                    while (pos < this.pattern.length()) {
                        if (this.pattern.charAt(pos) == '{') {
                            this.uriVars++;
                            pos++;
                        } else if (this.pattern.charAt(pos) == WILDCARD_ASTERISK) {
                            if (pos + 1 < this.pattern.length() && this.pattern.charAt(pos + 1) == WILDCARD_ASTERISK) {
                                this.doubleWildcards++;
                                pos += 2;
                            } else if (pos > 0 && !".*".equals(this.pattern.substring(pos - 1))) {
                                this.singleWildcards++;
                                pos++;
                            } else {
                                pos++;
                            }
                        } else {
                            pos++;
                        }
                    }
                }
            }

            /**
            *        URI             
            *
            * @return URI             
             */
            public int getUriVars() {
                return this.uriVars;
            }

            /**
            *                         
            *
            * @return                   
             */
            public int getSingleWildcards() {
                return this.singleWildcards;
            }

            /**
            *                         
            *
            * @return                   
             */
            public int getDoubleWildcards() {
                return this.doubleWildcards;
            }

            /**
            *                               (null     /**)
            *
            * @return                               
             */
            public boolean isLeastSpecific() {
                return (this.pattern == null || this.catchAllPattern);
            }

            /**
            *                      
            *
            * @return                      
             */
            public boolean isPrefixPattern() {
                return this.prefixPattern;
            }

            /**
            *                (URI          +                 + 2*               )
            *
            * @return          
             */
            public int getTotalCount() {
                return this.uriVars + this.singleWildcards + (2 * this.doubleWildcards);
            }

            /**
            *                                                                    1   
            *
            * @return       
             */
            public int getLength() {
                if (this.length == null) {
                    this.length = (this.pattern != null ?
                            VARIABLE_PATTERN.matcher(this.pattern).replaceAll("#").length() : 0);
                }
                return this.length;
            }
        }
    }


    /**
    *                                                          
     */
    private static class PathSeparatorPatternCache {

        /**
        *                (*)               
         */
        private final String endsOnWildCard;

        /**
        *                (**)               
         */
        private final String endsOnDoubleWildCard;

        /**
        *        PathSeparatorPatternCache
        *
        * @param pathSeparator                
         */
        public PathSeparatorPatternCache(String pathSeparator) {
            this.endsOnWildCard = pathSeparator + "*";
            this.endsOnDoubleWildCard = pathSeparator + SYMBOL_ASTERISK_ANY;
        }

        /**
        *                                     
        *
        * @return                               
         */
        public String getEndsOnWildCard() {
            return this.endsOnWildCard;
        }

        /**
        *                                     
        *
        * @return                               
         */
        public String getEndsOnDoubleWildCard() {
            return this.endsOnDoubleWildCard;
        }
    }

}