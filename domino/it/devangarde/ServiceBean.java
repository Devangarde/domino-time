package it.devangarde;

import org.json.simple.parser.JSONParser;

import java.util.Map;
import java.util.HashMap;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.net.URLDecoder;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ibm.domino.services.ResponseCode;
import com.ibm.domino.services.ServiceException;
import com.ibm.domino.services.rest.RestServiceEngine;
import com.ibm.xsp.extlib.component.rest.CustomService;
import com.ibm.xsp.extlib.util.ExtLibUtil;

import lotus.domino.*;

public class ServiceBean extends com.ibm.xsp.extlib.component.rest.CustomServiceBean {
	
	private RestServiceEngine engine;
	protected HttpServletRequest request;
	protected HttpServletResponse response;
	protected JSONObject payload;
	protected JSONObject body;
	protected Map<String, String> queryString;

   	protected Session session;
	protected Database db;
    
	public void renderService(CustomService service, RestServiceEngine engine) throws ServiceException {
		this.engine = engine;
		this.request = engine.getHttpRequest();
		this.response = engine.getHttpResponse();
		
		this.response.setHeader("Content-Type", "application/json; charset=UTF-8");
		
		this.body = new JSONObject();
		
		this.buildQueryString();
		this.buildPayload();
		
		try {
			this.session = ExtLibUtil.getCurrentSessionAsSigner();
			this.db = this.session.getCurrentDatabase();
			
            Method m = this.getClass().getDeclaredMethod(this.request.getMethod().toLowerCase());
            m.invoke(this);
		} catch (NoSuchMethodException e) {
			String method = this.request.getMethod();
			if (method.compareToIgnoreCase("head") == 0) return;
			throw new ServiceException(new Exception("Unsupported method: " + method), ResponseCode.BAD_REQUEST);
		} catch (InvocationTargetException e) {
            // unwrap and rethrow the real exception thrown inside get()/post()/etc.
            Throwable cause = e.getCause();
            /*System.out.println(e);
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
            */
            if (cause instanceof BadRequestException) {
            	this.fail(cause, ResponseCode.BAD_REQUEST);
            /*} else if (cause instanceof UnauthorizedException) {
            	this.fail(cause, ResponseCode.UNAUTHORIZED);*/
            } else {
            	this.fail(cause, ResponseCode.INTERNAL_ERROR);
            }
		} catch (Exception e) {
			e.printStackTrace();
			throw new ServiceException(e, e instanceof BadRequestException ? ResponseCode.BAD_REQUEST : ResponseCode.INTERNAL_ERROR);
		} finally {
			this.close();
		}
		
	}
	
	protected boolean isMethod(String method) {
		return (0 == this.request.getMethod().compareToIgnoreCase(method));
	}
	
    protected void fail(Throwable t, ResponseCode rc) {
    	// in console del server solo errori 500
        if (rc.httpStatusCode >= 500 && rc.httpStatusCode < 600) t.printStackTrace();
        ServiceException ex = new ServiceException((Exception) t, rc);
        this.engine.displayError(ex);
    }
	
    protected void close() {
        try {
            try {
                PrintWriter writer = this.response.getWriter();
                writer.write(this.body.toString());
                writer.close();
            } catch (IllegalStateException isex) {
                // getWriter not available when engine.displayError() was already called
            }

            this.db.recycle();
			this.session.recycle();

        } catch (Exception e) {
            e.printStackTrace();
            this.response.setStatus(500);
        }
    }
	
	protected String formatTimestamp(Object dateTime) throws Exception {
		if (dateTime == null) return null;
		if (dateTime instanceof DateTime) return dateTime.getDate().toInstant().toString();
		if (dateTime instanceof java.util.Date) return dateTime.toInstant().toString();
		throw new Exception("Unsupported DateTime object: " + dateTime.getClass().getCanonicalName());
	}
	
	private void buildQueryString() {
		this.queryString = new HashMap<>();
        String[] pairs = this.request.getQueryString().split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key;
            String value = "";
            
            // If '=' is present, split key and value, otherwise treat as key without value
            try {
	            if (idx > -1) {
	                key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
	                value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
	            } else {
	                key = URLDecoder.decode(pair, "UTF-8");
	            }
	            this.queryString.put(key, value);
            } catch (UnsupportedEncodingException ex) {
            	// TODO
            }
        }
	}
	
	private void buildPayload() {
		try (BufferedReader reader = this.request.getReader()) {
			JSONParser parser = new JSONParser();
			this.payload = new JSONObject((org.json.simple.JSONObject)parser.parse(reader));
		} catch (Exception e) {
			//e.printStackTrace();
		}
	}
	
}
