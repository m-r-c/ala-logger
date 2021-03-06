/**************************************************************************
 *  Copyright (C) 2010 Atlas of Living Australia
 *  All Rights Reserved.
 *
 *  The contents of this file are subject to the Mozilla Public
 *  License Version 1.1 (the "License"); you may not use this file
 *  except in compliance with the License. You may obtain a copy of
 *  the License at http://www.mozilla.org/MPL/
 *
 *  Software distributed under the License is distributed on an "AS
 *  IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  rights and limitations under the License.
 ***************************************************************************/

package org.ala.client.appender;

import org.ala.client.model.LogEventVO;
import org.ala.client.util.Constants;
import org.ala.client.util.RestfulClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.spi.LoggingEvent;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Log4J appender for JSON based REST Web Service.
 * 
 * @author MOK011
 *
 */
public class RestfulAppender extends AppenderSkeleton {

    public static final String LOGGER_CLIENT_PROPERTIES = "/data/logger-client/config/logger-client.properties";
    public static final String LOGGER_URL_PROPERTY = "logger_url";
    private String urlTemplate;
	private String username;
	private String password;
	private int timeout;

	private ObjectMapper serMapper;
	private ObjectMapper deserMapper;
	private RestfulClient restfulClient;
	
	public RestfulAppender(){
		super();
		restfulClient = new RestfulClient(timeout);
		        
        serMapper = new ObjectMapper();
        serMapper.getSerializationConfig().setSerializationInclusion(JsonSerialize.Inclusion.NON_NULL);
        deserMapper = new ObjectMapper();
        deserMapper.getDeserializationConfig().set(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        loadLoggerClientProperties();
	}

    private void loadLoggerClientProperties() {
        FileInputStream stream = null;
        try {
            Properties props = new Properties();
            File propsFile = new File(LOGGER_CLIENT_PROPERTIES);
            if (propsFile.exists()) {
                stream = new FileInputStream(propsFile);
                props.load(stream);
                if (!StringUtils.isBlank(props.getProperty(LOGGER_URL_PROPERTY))) {
                    urlTemplate = props.getProperty(LOGGER_URL_PROPERTY);
                    LogLog.debug("Log events will be written to [" + urlTemplate + "]");
                }
            }
            else {
                LogLog.warn("Cannot find logger client properties file " + LOGGER_CLIENT_PROPERTIES + ". Logger " +
                        "Service URL will be taken from the log4j.xml config file for the host application.");
            }
        }
        catch (Exception e) {
            LogLog.warn("Failed to load logger client properties file: " + e.getMessage() + ". Logger " +
                    "Service URL will be taken from the log4j.xml config file for the host application.");
            // not much else can be done here - the urlTemplate will be left blank, so any value provided in the
            // log4j.xml file will be used instead of the environment specific configuration property
        }
        finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                }
            }
        }
    }
	
	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}
	
	public void setUrlTemplate(String urlTemplate) {
        // only set the urlTemplate if it has not already been defined - see loadLoggerClientProperties()
        if (StringUtils.isBlank(this.urlTemplate)) {
            this.urlTemplate = urlTemplate;
        }
	}
	
	public void setUsername(String username) {
		this.username = username;
	}

	public void setPassword(String password) {
		this.password = password;
	}
	
	@Override
	protected void append(LoggingEvent event) {
		if (!checkEntryConditions()) {
			return;
		}

		if (!isAsSevereAsThreshold(event.getLevel())){
			return;
		}
		sendRestRequest(event);
	}

	private boolean checkEntryConditions() {
		if (urlTemplate == null) {
			LogLog.error("No 'urlTemplate' for [" + name + "]");
			return false;						
		}				
		return true;
	}
	
	
	private int sendRestRequest(LoggingEvent event) {
		PostMethod post = null;
		int statusCode = 0;
		String message = null;
		LogEventVO vo = null;

		try {
			Object object = event.getMessage();
        	if(object instanceof LogEventVO){       		
        		//convert to JSON
        		message = serMapper.writeValueAsString(object); 
        	}
        	else if(event.getMessage() instanceof String){
        		message = (String)object;
        		if(message.startsWith("Discarded")){        		    
        		    //NQ:2014-02-13 - This is a special type of message that was sent from the AsynAppender to let us know that 
        		    //some messages were discarded
        		    return 0;
        		}
        		//validate json string
        		vo = deserMapper.readValue(message, LogEventVO.class);        		
        	}
        	
        	if(restfulClient == null){
        		restfulClient = new RestfulClient(timeout);
        	}

            LogLog.debug("Posting log event to URL [" + urlTemplate + "]");
        	Object[] array = restfulClient.restPost(urlTemplate, message, constructHttpHeaders(event));
        	if(array != null && array.length > 0){
        		statusCode = (Integer)array[0];
        	}
        } 
        catch(Exception e) {
        	statusCode = HttpStatus.SC_NOT_ACCEPTABLE;
	        LogLog.error("Could not send message from RestfulAppender [" + name + "],\nMessage: " + event.getMessage(), e);
        } finally {
        	vo = null; //waiting for gc.
        	if(post != null){
        		post.releaseConnection();
        	}
        }
        return statusCode;
	}

	private Map<String, String> constructHttpHeaders(LoggingEvent event) {
		Map<String, String> headers = new HashMap<String, String>();

		if (event != null) {
			String userAgent = (String) event.getMDC(Constants.USER_AGENT_PARAM);
			if (StringUtils.isBlank(userAgent)) {
				userAgent = Constants.UNDEFINED_USER_AGENT_VALUE;
			}
			headers.put(Constants.USER_AGENT_PARAM, userAgent);
		}

		return headers;
	}

	public void close() {
		restfulClient = null;
		//This is a recursive call to the same method. Would cause a stack overflow.  
		//this.close();
	}

	public boolean requiresLayout() {
		return false;
	}
	
}
