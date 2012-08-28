/*
 * Copyright (C) 2007-2009 Inverse inc. and Ludovic Marcotte
 * 
 * Author: Ludovic Marcotte <lmarcotte@inverse.ca>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package ca.inverse.sogo.engine.source;

import java.sql.ResultSet;
import java.sql.PreparedStatement;

import com.funambol.framework.engine.SyncItem;
import com.funambol.framework.engine.source.SyncContext;
import com.funambol.framework.logging.FunambolLogger;

public class SOGoACLManager {

	public static final int SOGoACLNone = 0;
	public static final int SOGoACLObjectCreator = 1;
	public static final int SOGoACLObjectEraser = 2;
	
	public static final int SOGoACLPublic = 4;
	public static final int SOGoACLConfidential = 8;
	public static final int SOGoACLPrivate = 16;
	
	public static final int SOGoACLViewer = 32;
	public static final int SOGoACLDAndTViewer = 64;
	public static final int SOGoACLResponder = 128;
	public static final int SOGoACLModifier = 256;
	
	public static final int SOGoACLOwner = 512;
	
	/**
	 *
	 */
	public static boolean hasACL(int rights, int acl) {
		if ((rights & acl) == acl)
			return true;
		
		return false;
	}
	
	/**
	 * We get something like this:
	 * 
	 *  value is structured like this:  sogo1:Calendar/2230-4A609280-1-3C4950E0
	 * 
	 */
	public static int getACL(SOGoSyncSource source, SyncContext context, FunambolLogger log, String location, String value, int classification) {
		int acl;
		
		acl = SOGoACLNone;
		
		try {
			String c_uid, c_object, role;
			PreparedStatement s;
			ResultSet rs;
			int i;
			
			i = value.indexOf(':');
			c_uid = value.substring(0, i);
			
			// If we are the owner of the collection, let's return immediately
			if (c_uid.equalsIgnoreCase(context.getPrincipal().getUsername()))
				return SOGoACLOwner;
			
			c_object = '/' + c_uid + '/' + value.substring(i+1);
			location = location + "_acl";
			
			s = source.getDBConnection().prepareStatement("SELECT c_role FROM " + location + " WHERE c_uid = ? AND c_object = ?");
			s.setString(1, context.getPrincipal().getUsername());
			s.setString(2, c_object);
			rs = s.executeQuery();
			
			log.info("Looking up roles for " + context.getPrincipal().getUsername() + " object: " + c_object + " tablename: " + location);
			
			while (rs.next()) {
				role = rs.getString(1);
				log.info("Found role: " + role);
				if (role.equalsIgnoreCase("None")) {
					acl = SOGoACLNone;
					break;
				}
				else if (role.equals("ObjectCreator"))
					acl = acl | SOGoACLObjectCreator;
				else if (role.equals("ObjectEraser"))
					acl = acl | SOGoACLObjectEraser;
				else if (classification == SOGoACLPublic && role.startsWith("Public"))
					acl = acl | getSubRole(role, SOGoACLPublic);
				else if (classification == SOGoACLPrivate && role.startsWith("Private"))
					acl = acl | getSubRole(role, SOGoACLPrivate);
				else if (classification == SOGoACLConfidential && role.startsWith("Confidential"))
					acl = acl | getSubRole(role, SOGoACLConfidential);
			}
			
			rs.close();
			s.close();
			
		} catch (Exception e) {
			log.error("Exception occured in getACL(): " + e.toString(), e);
		}
		
		return acl;
	}
	
	/**
	 * 
	 * @param s
	 * @param primaryRole
	 * @return
	 */
	private static int getSubRole(String s, int primaryRole) {
		int role;
		
		role = SOGoACLNone;
		
		if (s.endsWith("DAndTViewer"))
			role = primaryRole | SOGoACLDAndTViewer;
		else if (s.endsWith("Viewer"))
			role = primaryRole | SOGoACLViewer;
		else if (s.endsWith("Responder"))
			role = primaryRole | SOGoACLResponder;
		else if (s.endsWith("Modifier"))
			role = primaryRole | SOGoACLModifier;
		
		return role;
	}
	
	/**
	 * 
	 * @param item
	 * @param source
	 * @param context
	 * @param log
	 * @param location
	 * @return
	 */
	public static int getRoleFromClassification(SyncItem item, SOGoSyncSource source, SyncContext context, FunambolLogger log, String location) {
		int role;
		
		role = SOGoACLNone;
		
		log.info("Getting classification for key: " + SOGoKey.encodeString((String)item.getKey().getKeyValue()) + " in location: " + location);
		try {
			PreparedStatement s;
			ResultSet rs;
			
			s = source.getDBConnection().prepareStatement("SELECT c_classification FROM " + location + "_quick WHERE c_name = ?");
			s.setString(1, SOGoKey.encodeString((String)item.getKey().getKeyValue()));
			rs = s.executeQuery();

			// 0 -> public
			// 1 -> private
			// 2 -> confidential
			if (rs.next()) {
				if (rs.getInt(1) == 1)
					role = SOGoACLPrivate;
				else if (rs.getInt(1) == 2)
					role = SOGoACLConfidential;
				else	
					role = SOGoACLPublic;
				
				log.info("Item found, returned role: " + role);
			} else {
				log.info("Item not found in getRoleFromClassification()");
			}
			
			rs.close();
			s.close();
			
		} catch (Exception e) {
			log.error("Exception occured in getRoleFromClassification(): " + e.toString(), e);
		}
		
		return role;
	}
}
