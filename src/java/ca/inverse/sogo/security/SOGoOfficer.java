/*
 * Copyright (C) 2003-2007 Funambol, Inc.
 * 
 * Modification made by: Ludovic Marcotte <ludovic@inverse.ca>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the Honest Public License, as published by
 * Funambol, either version 1 or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY, TITLE, NONINFRINGEMENT or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the Honest Public License for more details.
 *
 * You should have received a copy of the Honest Public License
 * along with this program; if not, write to Funambol,
 * 643 Bair Island Road, Suite 305 - Redwood City, CA 94063, USA
 *  
 * This file is vaguely inspired from ExchangeOfficer.java included in the
 * Funambol Exchange connector.
 */
package ca.inverse.sogo.security;

import java.security.Principal;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.funambol.framework.core.Authentication;
import com.funambol.framework.core.Cred;
import com.funambol.framework.security.Officer;
import com.funambol.framework.security.Sync4jPrincipal;
import com.funambol.framework.server.store.NotFoundException;
import com.funambol.framework.server.store.PersistentStore;
import com.funambol.framework.server.store.PersistentStoreException;
import com.funambol.framework.server.Sync4jUser;
import com.funambol.framework.tools.Base64;
import com.funambol.framework.logging.Sync4jLogger;
import com.funambol.framework.filter.WhereClause;
import com.funambol.server.config.Configuration;
import com.funambol.server.admin.UserManager;
import ca.inverse.sogo.engine.source.SOGoUser;
//import org.apache.jackrabbit.webdav.client.methods.*;
//import java.io.ByteArrayInputStream;
//import org.apache.commons.httpclient.*;
//import org.apache.commons.httpclient.auth.*;
//import org.apache.jackrabbit.webdav.*;
//import org.apache.jackrabbit.webdav.xml.*;
//import org.w3c.dom.*;
//import javax.xml.parsers.*;
import java.net.*;
public class SOGoOfficer implements Officer, java.io.Serializable {

	// Static variables
	private static final long serialVersionUID = 1L;
	private static final String DEFAULT_USER_ROLE = "sync_user";

	// Private ivars
    private Logger _log = Sync4jLogger.getLogger();
    private UserManager _userManager = null;
    private PersistentStore _ps  = null;
    private String _host = null;
    private String _port = null;

    /**
     * 
     */
    public String getClientAuth() {
        return Cred.AUTH_TYPE_BASIC;
    }

    /**
     * 
     */
    public String getServerAuth() {
        return Cred.AUTH_NONE;
    }

    /**
     * 
     * @return
     */
    public String getHost() {
        return _host;
    }

    /**
     * 
     * @param h
     */
    public void setHost(String h) {
        this._host = h;
    }
    
    public String getPort() {
    	return _port;
    }
    
    public void setPort(String p) {
    	this._port = p;
    }

    /**
     * 
     */
    public SOGoUser authenticateUser(Cred credential) {

        if (_log.isLoggable(Level.FINEST)) {
            _log.finest(" ExchangeOfficer authenticate() Start");
        }

        Configuration config = Configuration.getConfiguration();
        _ps = config.getStore();
        _userManager = config.getUserManager();
        String type = credential.getType();

        if ((Cred.AUTH_TYPE_BASIC).equals(type)) {
            return authenticateBasicCredential(credential);
        }
        return null;
    }

    /**
     * 
     */
    public AuthStatus authorize(Principal principal, String resource) {
    	return Officer.AuthStatus.AUTHORIZED;
    }

    /**
     * 
     */
    public void unAuthenticate(Sync4jUser user) {
    }

    /**
     * 
     * @param credentials
     * @return
     */
    public boolean isAccountExpired(Cred credentials) {
        return false;
    }

    /**
     * 
     */
    private SOGoUser authenticateBasicCredential(Cred credential) {

        String username, password;

        Authentication auth = credential.getAuthentication();
        String deviceId     = auth.getDeviceId();
        String credentials  = auth.getData();
        String userpwd      = new String(Base64.decode(auth.getData()));
        SOGoUser user = new SOGoUser();
        
        int p = userpwd.indexOf(':');

        if (p == -1) {
            username = userpwd;
            password = "";
        } else {
            username = (p > 0) ? userpwd.substring(0, p) : "";
            password = (p == (userpwd.length() - 1)) ? "" : userpwd.substring(p + 1);
        }
        
        if (_log.isLoggable(Level.FINEST)) {
            _log.finest("Username: "    + username    );
            _log.finest("Credentials: " + credentials );
        }

        if (!checkSOGoCredentials(this.getHost(), username, credentials, user)) {
        	return null;
        }
 
        try {
            if (existsUser(username)) {
                if (_log.isLoggable(Level.INFO)) {
                    _log.info("User with '" + username + "' exists.");
                }
            }
            else {
                insertUser(username, " ");
                if (_log.isLoggable(Level.INFO)){
                    _log.info("User '" + username + "' created.");
                }
            }
        } catch (PersistentStoreException e) {
            if (_log.isLoggable(Level.SEVERE)) {
                _log.severe("Error inserting a new user: " + e.getMessage());
            } 
            if (_log.isLoggable(Level.INFO)) {
                _log.log(Level.INFO, "authenticateBasicCredential", e);
            }
            return null;
        }

        try {
            if (!existsPrincipal(username, deviceId)) {
                credential.getAuthentication().setPrincipalId(
                    insertPrincipal(username, deviceId));
            }
        } catch(PersistentStoreException e) {
            if (_log.isLoggable(Level.SEVERE)) {
                _log.severe("Error inserting a new principal: " + e.getMessage());
            }
            if (_log.isLoggable(Level.INFO)) {
                _log.log(Level.INFO, "authenticateBasicCredential", e);
            }
            return null;
        }
        
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }

    /**
     * 
     * @param host
     * @param username
     * @param credentials
     * @return
     */
    private boolean checkSOGoCredentials(String host, String username, String credentials, SOGoUser user) {
    	try {
    		URLConnection conn;
    		URL url;
        	// host has the following format:  sogo.acme.com
        	// We have to rebuild the URL using: http://sogo.acme.com/SOGo/dav/<username>/freebusy.ifb
    		//url = new URL("http://" + host + ":8999/SOGo/dav/" + username + "/freebusy.ifb");    		
    	    url = new URL("http", host, Integer.parseInt(_port), "/SOGo/dav/" + username + "/freebusy.ifb");
    		conn = url.openConnection();
    	    conn.setRequestProperty("Authorization", "Basic " + credentials);
    	    conn.getInputStream();
    		
    		//ClassLoader savedClassLoader = Thread.currentThread().getContextClassLoader();
    		//Thread.currentThread().setContextClassLoader( this.getClass().getClassLoader() );
    		//Class.forName("org.apache.xerces.parsers.XML11Configuration");
    		//System.setProperty("javax.xml.parsers.DocumentBuilderFactory", "org.apache.xerces.jaxp.DocumentBuilderFactoryImpl");
    		
//    		PropFindMethod pf;
//            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
//            DocumentBuilder builder = factory.newDocumentBuilder();
//
//            String s = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
//                                "<D:propfind xmlns:D=\"DAV:\">" +
//                                  "<D:prop>" +
//                                    "<D:owner/>" +
//                                  "</D:prop>" +
//                                "</D:propfind>";
//
//            ByteArrayInputStream stream;
//            stream = new ByteArrayInputStream(s.getBytes());
//            Document doc = builder.parse(stream);
//
//            pf = new PropFindMethod("http://" + host + "/SOGo/dav/" + username + "/freebusy.ifb", DavConstants.PROPFIND_BY_PROPERTY, DavConstants.DEPTH_1);
//            pf.setRequestBody(doc);
//
//            HttpClient client = new HttpClient();
//            HostConfiguration hc = new HostConfiguration();
//            hc.setHost(host, 80);
//
//            client.getParams().setAuthenticationPreemptive(true);
//            Credentials defaultcreds = new UsernamePasswordCredentials(username, password);
//            client.getState().setCredentials(new AuthScope(host, 80, AuthScope.ANY_REALM), defaultcreds);
//            client.executeMethod(hc, pf);
//        	
//            Document d = pf.getResponseBodyAsDocument();
//
//            Node n = (Node)d.getFirstChild().getFirstChild();
//
//            NodeList l;
//            l = ((Element)n).getElementsByTagName("D:owner");
//            l = ((Element)l.item(0)).getElementsByTagName("D:href");
//            s =  DomUtil.getText((Element)l.item(0));
//
//            int a;
//
//            a = s.lastIndexOf('/', s.length()-2);
//            s =  s.substring(a+1);
//            if (s.endsWith("/")) s = s.substring(0, s.length()-1);
//            System.out.println("userid = " + s);

            user.setUserID(username);
            
            //Thread.currentThread().setContextClassLoader( savedClassLoader );


    	    return true;
    	} catch (Exception e) {
    	   	_log.log(Level.WARNING, "Unable to check SOGo credentials. Invalid host, username or password.", e);
    	   	_log.info("The host that was used is: " + host);
    	   	_log.info("The username that was used is: " + username);
    	   	//_log.info("The password that was used is: " + password);
    	}
    	
    	return false;
    }
    

    /**
     * 
     * @param userName
     * @return
     * @throws PersistentStoreException
     */
    private boolean existsUser(String userName)
    throws PersistentStoreException {
        Sync4jUser[] users;
        WhereClause wc;
        String value[] = new String[]{userName};
        wc = new WhereClause("username", value, WhereClause.OPT_EQ, true);
        users = _userManager.getUsers(wc);
        _log.info("User with username " + userName + (users.length > 0 ? " exists" : " does not exist"));        
        return users.length>0;
    }

    /**
     * 
     * @param userName
     * @param password
     * @throws PersistentStoreException
     */
    private void insertUser(String userName, String password)
    throws PersistentStoreException {
        Sync4jUser user = new Sync4jUser();
        user.setUsername(userName);
        user.setPassword(password);
        user.setRoles(new String[] {DEFAULT_USER_ROLE});
        _userManager.insertUser(user);
        _log.info("Created username '" + userName + "'");
    }

    /**
     * 
     * @param userName
     * @param deviceId
     * @return
     * @throws PersistentStoreException
     */
    private boolean existsPrincipal(String userName, String deviceId)
    throws PersistentStoreException {
        Principal principal;
        try {
            principal = Sync4jPrincipal.createPrincipal(userName, deviceId);
            _ps.read(principal);
            _log.info("Principal for " + userName + ":" + deviceId +" found!");
            return true;
        } catch(NotFoundException e) {
        	_log.info("Principal for " + userName + ":" + deviceId +" not found");
            return false;
        }
     }

    /**
     * 
     * @param userName
     * @param deviceId
     * @return
     * @throws PersistentStoreException
     */
    private long insertPrincipal(String userName, String deviceId)
    throws PersistentStoreException {
        Sync4jPrincipal principal = 
            Sync4jPrincipal.createPrincipal(userName, deviceId);
        	_log.info("Created principal '" + userName + "/" + deviceId + "'");
        try {
            _ps.store(principal);
        } catch (PersistentStoreException e) {
            _log.throwing(getClass().getName(), "insertPrincipal", e);
            if (_log.isLoggable(Level.SEVERE)) {
                _log.severe("Error creating new principal: " + e);
            }
            if (_log.isLoggable(Level.INFO)) {
                _log.log(Level.INFO, "authenticateBasicCredential",  e);
            }
            throw e;
        }
        return principal.getId();
    }
}

