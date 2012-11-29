/*
 * Copyright (C) 2007-2010 Inverse inc. and Ludovic Marcotte
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

import java.io.ByteArrayInputStream;
import java.text.*;
import java.util.*;
import java.sql.*;

import com.funambol.common.pim.common.PropertyWithTimeZone;
import com.funambol.framework.logging.FunambolLogger;
import com.funambol.common.pim.common.Property;
import com.funambol.common.pim.contact.Contact;
import com.funambol.common.pim.icalendar.*;
import com.funambol.common.pim.model.*;
import com.funambol.common.pim.sif.SIFCParser;
import com.funambol.common.pim.sif.SIFCalendarParser;
import com.funambol.common.pim.utility.TimeUtils;
import com.funambol.common.pim.vcard.VcardParser;
import com.funambol.framework.core.DataStore;
import com.funambol.framework.engine.source.*;
import com.funambol.framework.server.*;
import com.funambol.common.pim.converter.*;
import com.funambol.common.pim.calendar.*;
import com.funambol.common.pim.calendar.Calendar;
import com.funambol.common.pim.converter.TaskToSIFT;
import com.funambol.common.pim.contact.*;
import com.funambol.framework.engine.SyncItem;
import org.json.simple.*;


@SuppressWarnings(value={"unchecked"})
public class SOGoUtilities {

	// Constants
	static final int RX = 1;
	static final int TX = 2;
	// Classification codes mapping
	// iCal-vCard		; Funambol	; SOGo
	// PUBLIC		; 0		; 0
	// X-PERSONAL		; 1		; N/A
	// PRIVATE		; 2		; 1
	// CONFIDENTIAL		; 3		; 2
	public static final Short SENSITIVITY_PRIVATE      = 2; // OlSensitivity.olPrivate
	public static final Short SENSITIVITY_CONFIDENTIAL = 3; // OlSensitivity.olConfidential
	public static final Short SOGO_SENSITIVITY_PRIVATE       = 1;
	public static final Short SOGO_SENSITIVITY_CONFIDENTIAL  = 2;
	
	/**
	
	Sample output of c_settings :

	 Calendar = {
         DragHandleVertical = 122;
         FolderColors = {
             "flachapelle:Calendar/personal" = "#FF6666";
             "lmarcotte:Calendar/2633-49F5AB80-1-ECD55D0" = "#CC33FF";
             "lmarcotte:Calendar/personal" = "#99FF99";
         };
         FolderShowAlarms = {
             "lmarcotte:Calendar/personal" = YES;
         };
         FolderShowTasks = {
             "lmarcotte:Calendar/personal" = YES;
         };
         FolderSyncTags = {
             "lmarcotte:Calendar/2633-49F5AB80-1-ECD55D0" = "a bb oo";
             "lmarcotte:Calendar/personal" = toto;
         };
         InactiveFolders = (
         );
         SubscribedFolders = (
             "flachapelle:Calendar/personal"
         );
         View = weekview;
     };
     
     As an example, for :
     
     FolderSyncTags = {
             "lmarcotte:Calendar/2633-49F5AB80-1-ECD55D0" = "a bb oo";
     };
  
     we'll eventually check, in sogo_folder_info:
     
     c_folder_type = 'Appointment' AND
     c_path2 = 'lmarcotte' AND
     c_path ENDS WITH 'Calendar/2633-49F5AB80-1-ECD55D0'
     
	 */
	public static HashMap getSyncTags(SOGoSyncSource source, int type, SyncContext context, String username , FunambolLogger log) {
		Vector<String> folders;
		HashMap h;
			
		h = new HashMap();
		
		// For now, we only support sync tags for calendars (events and tasks). So if we detect
		// a contact source, we return immediately.
		if (type == SOGoSyncSource.SOGO_CONTACT)
			return h;
		
		folders = new Vector<String>();
		
		try {
			ResultSet rs;
			Statement s;
			
			// We first fetch the user's time zone
			s = source.getDBConnection().createStatement();
 			rs = s.executeQuery("SELECT c_settings FROM sogo_user_profile WHERE c_uid = '" + username + "'");
		
			if (rs.next()) {
				String data;
				
				data = rs.getString(1);
				
				// We've got no c_settings, return immediately
				if (data == null || data.length() == 0) {
					rs.close();
					s.close();
					return h;
				}
				
				try {
					JSONObject json, jsonCalendar;
					log.info("About to parse: " + data);	
					json = (JSONObject)JSONValue.parse(data);
					
					if (json != null) {
					jsonCalendar = (JSONObject)json.get("Calendar");
					
					if (jsonCalendar != null) {
						String key, value;
						Iterator it;
						
						json = (JSONObject)jsonCalendar.get("FolderSyncTags");
						if (json != null) {
							it = json.keySet().iterator();
							while (it != null && it.hasNext()) {
								key = it.next().toString();
								value = (String)json.get(key);
								h.put(value.toLowerCase(), key);
							}
						}
						
						json = (JSONObject)jsonCalendar.get("FolderSynchronize");
						if (json != null) {
							it = json.keySet().iterator();
							while (it != null && it.hasNext()) {
								folders.add(it.next().toString());
							}
						}	
					}
					}
				} catch (Exception pe) {
					log.error("Exception occured in getSyncTags(): " + pe.toString(), pe);
				}
			}
			
			// We cleanup what we have to sync, if necessary. We only keep
			// the keys that are actually in "folders". 
			h.values().retainAll(folders);
			rs.close();
			s.close();

		} catch (Exception e) {
			log.error("Exception occured in getSyncTags(): " + e.toString(), e);
		}
		
		return h;
	}
	
	/**
	 * 
	 * For now, we only return the tags associated to *calendars*.
	 * 
	 * @param source
	 * @param sync_tags
	 * @param log
	 * @return
	 */
	public static HashMap getSyncTagsLocation(SOGoSyncSource source, SyncContext context, HashMap sync_tags, FunambolLogger log) {
		HashMap h;
		
		h = new HashMap();
		
		try {
			String tag, c_path, c_path2, value;
			Vector<String> allTags;
			Iterator tags;
			ResultSet rs;
			Statement s;
			Set s1, s2;
			int i;
			
			// We first fetch the user's time zone
			s = source.getDBConnection().createStatement();
			
			s1 = sync_tags.keySet();
			tags = s1.iterator();
			
			while (tags.hasNext()) {
				tag = (String)tags.next();
				value = (String)sync_tags.get(tag);
				
				// The value we get is structured like: sogo1:Calendar/2230-4A609280-1-3C4950E0
				i = value.indexOf(':');
				c_path2 = value.substring(0, i);
				c_path = value.substring(i+1);
				
				// We always skip the 'personal' folders (for the current user) and we only get the 'Appointment' collections for
				// now as it's not possible to set tags on additional address books from SOGo Web.
				if (c_path2.equalsIgnoreCase(context.getPrincipal().getUsername()))
					rs = s.executeQuery("SELECT c_path, c_location FROM sogo_folder_info WHERE c_path4 != 'personal' AND c_folder_type = 'Appointment' AND c_path2 = '" + c_path2 + "'");
				else
					rs = s.executeQuery("SELECT c_path, c_location FROM sogo_folder_info WHERE c_folder_type = 'Appointment' AND c_path2 = '" + c_path2 + "'");

				
				while (rs.next()) {
					if (rs.getString(1).endsWith(c_path)) {
						log.info("getSyncTagsLocation - caching key = " + tag + " for location: " + rs.getString(2).substring(rs.getString(2).lastIndexOf('/')+1));
						h.put(tag, rs.getString(2).substring(rs.getString(2).lastIndexOf('/')+1));
					}
				}
				rs.close();
			}
			
			// We now clean all the crap in sync_tags - calendars might have been deleted 
			// and FolderSyncTags might be out of sync.
			allTags = new Vector(sync_tags.keySet());
			s2 = h.keySet();
			
			for (i = allTags.size()-1; i >= 0; i--) {
				tag = (String)allTags.get(i);
				if (!s2.contains(tag))
					s1.remove(tag);
			}
			
			s.close();

		} catch (Exception e) {
			log.error("Exception occured in getSyncTagsLocation(): " + e.toString(), e);
		}

		return h;
	}
	
	/**
	 * This method is used to guess the best database driver
	 * based on the supplied URI. The format is usually:
	 * 
	 * jdbc:postgresql:...
	 * jdbc:oracle:thin:...
	 * 
	 * It then initialize the driver with the proper parameters and
	 * establish a database connection, which is returned.
	 */
	public static Connection initDatabaseDriver(SOGoSyncSource source, FunambolLogger log) {
        Connection con;
		Properties props;
		String s, uri;
		int a, b;
		
		props = new Properties();
		uri = source.getDatabaseURL();
		a = uri.indexOf(':');
		b = uri.indexOf(':', a+1);
		s = uri.substring(a+1, b);

		if (s.equalsIgnoreCase("oracle")) {
			s = "oracle.jdbc.OracleDriver";
			
			// For Oracle, we try to load big strings as CLOB. This works
			// with the ojdbc14.jar (Oracle 10g 10.1.0.2.0) driver.
			props.put("SetBigStringTryClob", "true");
		} else if (s.equalsIgnoreCase("mysql")) {
			s = "com.mysql.jdbc.Driver";
		} 
		else {
			s = "org.postgresql.Driver";
		}
		
		props.put("user", source.getDatabaseUsername());
		props.put("password", source.getDatabasePassword());
		
		try {
			log.info("Loading the JDBC driver for URL: " + source.getDatabaseURL());
			Class.forName(s);

            con = DriverManager.getConnection(uri, props);

            // MySQL driver defaults to autocommit, so make sure it's turned off
            if (con.getAutoCommit()) {
                con.setAutoCommit(false);
            }

			return con;
		} catch (Exception e) {
			log.error("Couldn't find the driver! (" + s + ") or connect to the database: " + e.toString(), e);
		}

		return null;
	}
	
	/**
	 * 
	 * @param content
	 * @param name
	 * @param log
	 * @return
	 */
	public static VComponent getVComponentFromContent(String content, String name, FunambolLogger log) {	
		VComponent c, component;
		ICalendarParser p;
		List l;
		int i;

		try {
			p = new ICalendarParser(new ByteArrayInputStream(content.getBytes()));
			component = p.ICalendar();
			l = component.getAllComponents();

			for (i = 0; i < l.size(); i++) {
				c = (VComponent)l.get(i);
				if (c.getVComponentName().equalsIgnoreCase(name)) {
					return c;
				}
			}
		} catch (Exception e) {
			log.error("Exception occured in getVComponentFromContent(): " + e.toString(), e);
		}

		return null;
	}
	
	/**
	 * 
	 * @param item
	 * @param source
	 * @param type
	 * @param log
	 * @return
	 */
	public static CalendarContent getCalendarContentFromSyncItem(String content, String name, SyncItem item, SOGoSyncSource source, int type, FunambolLogger log) {	
		CalendarContent cc;
		
		cc = null;
		
		try {
			switch (type) {
			case SOGoSyncSource.VCALENDAR_VERSION_20:
			case SOGoSyncSource.VCALENDAR_VERSION_10:
				VComponent c;
				c = getVComponentFromContent(content, name, log);
				cc = new VCalendarContentConverter(null, source.getDeviceCharset(), true).vcc2cc((VCalendarContent)c, true);
				break;
			case SOGoSyncSource.X_S4J_SIFE:
			case SOGoSyncSource.X_S4J_SIFT:
				SIFCalendarParser p;
				p = new SIFCalendarParser(new ByteArrayInputStream(item.getContent()));
				cc = p.parse().getCalendarContent();
				break;
			}
		} catch (Exception e) {
			log.error("Exception occured in getCalendarContentFromSyncItem() - couldn't get Calendar from the syncItem: " + e.toString(), e);
			cc = null;
		}
		
		return cc;
	}
	
	/**
	 * 
	 * @param item
	 * @param type
	 * @param log
	 * @return
	 */
	public static Contact getContactFromSyncItem(SyncItem item, int type, FunambolLogger log) {
		Contact c;

		c = null;

		try {
			switch (type) {
			case SOGoSyncSource.VCARD_VERSION_30:
			case SOGoSyncSource.VCARD_VERSION_21:
			{
				VcardParser p;
				p = new VcardParser(new ByteArrayInputStream(item.getContent()), null, null);
				c = p.vCard();
			}
			break;

			case SOGoSyncSource.X_S4J_SIFC:
			{
				SIFCParser p;
				p = new SIFCParser(new ByteArrayInputStream(item.getContent()));
				c = p.parse();
			}
			break;
			}
		} catch (Exception e) {
			log.error("Exception occured in getContactFromSyncItem() - couldn't get Contact from the syncItem: " + e.toString(), e);
			c = null;
		}

		return c;
	}
	
	/**
	 * 
	 * @param context
	 * @return
	 */
	public static boolean getDeviceUTC(SyncContext context) {
		Sync4jDevice device;
		
		device = context.getPrincipal().getDevice();
		
		if (device.getCapabilities().getDevInf().getUTC() != null && device.getCapabilities().getDevInf().getUTC().booleanValue()) {
			return true;
		}
		
		return false;
	}
	
	/**
	 * 
	 * @param c
	 * @return
	 */
	public static String getPreferredCity(Contact c) {
		String s;
		
		s = c.getBusinessDetail().getAddress().getCity().getPropertyValueAsString();
		
		if (s == null) {
			s = c.getPersonalDetail().getAddress().getCity().getPropertyValueAsString();
		}
		
		return s;
	}

	/**
	 * 
	 * @param item
	 * @return
	 */
	public static String getPreferredEmail(Contact c) {
		String s;
		List l;
		
		l = c.getBusinessDetail().getEmails();
		
		if (l.size() > 0) {
			s = ((Email)l.get(0)).getPropertyValueAsString();
			
			if (s != null && s.length() > 0)
				return s;
		}
				
		l = c.getPersonalDetail().getEmails();
		
		if (l.size() > 0) {
			s = ((Email)l.get(0)).getPropertyValueAsString();
			
			if (s != null && s.length() > 0)
				return s;
		}
		
		return null;
	}
	
	/**
	 * 
	 * @param context
	 * @param syncSourceType
	 * @param way
	 * @return
	 */
	public static int getPreferredItemType(SyncContext context, int syncSourceType, int way) {
		ArrayList<String> l_types, l_versions;
		ArrayList dataStores;
		DataStore ds;
		int i, type;
		
		// First, we get the supported types from our data store
		// and also their version numbers.
		try {
			dataStores = context.getPrincipal().getDevice().getCapabilities().getDevInf().getDataStores();
		} catch (Exception e) {
			// We haven't been able to get any device, capabilities or data stores. We simply
			// assume our connecting client only supports the SIF standard.
			switch (syncSourceType) {
			case SOGoSyncSource.SOGO_CONTACT:
				return SOGoSyncSource.X_S4J_SIFC;
			case SOGoSyncSource.SOGO_EVENT:
				return SOGoSyncSource.X_S4J_SIFE;
			default:
				return SOGoSyncSource.X_S4J_SIFT;
			}
		}
		
		l_versions = new ArrayList<String>();
		l_types = new ArrayList<String>();
		
		for (i = 0; i < dataStores.size(); i++) {
			ds = (DataStore)dataStores.get(i);
			
			if (way == RX) {
				l_types.add(ds.getRxPref().getCTType().trim());
				l_versions.add(ds.getRxPref().getVerCT().trim());
			} else {
				l_types.add(ds.getTxPref().getCTType().trim());
				l_versions.add(ds.getTxPref().getVerCT().trim());
			}
		}

		// Based on the current sync source type, we return the most appropriate
		// item's content type. This still is a guess as we have no way on linking
		// the Device's capabilities with a current SyncSource.
		switch (syncSourceType) {
		case SOGoSyncSource.SOGO_EVENT:
			if (l_types.contains("text/x-s4j-sife")) {
				type = SOGoSyncSource.X_S4J_SIFE;
			}
			// Let's assume we've got text/x-vcalendar
			else if (l_versions.contains("2.0")) {
				type = SOGoSyncSource.VCALENDAR_VERSION_20;
			}
			else {
				type = SOGoSyncSource.VCALENDAR_VERSION_10;
			}
			break;
		case SOGoSyncSource.SOGO_TODO:
			if (l_types.contains("text/x-s4j-sift")) {
				type = SOGoSyncSource.X_S4J_SIFT;
			}
			// Let's assume we've got text/x-vcalendar
			else if (l_versions.contains("2.0")) {
				type = SOGoSyncSource.VCALENDAR_VERSION_20;
			}
			else {
				type = SOGoSyncSource.VCALENDAR_VERSION_10;
			}
			break;
		case SOGoSyncSource.SOGO_CONTACT:
		default:
			if (l_types.contains("text/x-s4j-sifc")) {
				type = SOGoSyncSource.X_S4J_SIFC;
			}
			// Let's assume we've got text/x-vcard
			else if (l_versions.contains("3.0")) {
				type = SOGoSyncSource.VCARD_VERSION_30;
			}
			else {
				type = SOGoSyncSource.VCARD_VERSION_21;
			}
			break;
		}
		
		return type;
	}
	
	/**
	 * 
	 * @param c
	 * @return
	 */
	public static String getPreferredPhone(Contact c) {
		List l;
		
		l = c.getBusinessDetail().getPhones();
		
		if (l.size() > 0) {
			return ((Phone)l.get(0)).getPropertyValueAsString();
		}
		
		l = c.getPersonalDetail().getPhones();
		
		if (l.size() > 0) {
			return ((Phone)l.get(0)).getPropertyValueAsString();
		}
		
		return null;
	}
	
	/**
	 * 
	 * @param source
	 * @param context
	 * @param log
	 * @return
	 */
	public static TimeZone getUserTimeZone(SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		String tz;
		
		tz = null;
		
		try {
			ResultSet rs;
			Statement s;
			
			// We first fetch the user's time zone
			s = source.getDBConnection().createStatement();
 			rs = s.executeQuery("SELECT c_defaults FROM sogo_user_profile WHERE c_uid = '" + context.getPrincipal().getUsername() + "'");
		
			if (rs.next()) {
				String data;
				Object o;

				data = rs.getString(1); 
				o = null;
				
				try {
					JSONObject json;

					json = (JSONObject)JSONValue.parse(data);
					
					try {
						o = json.get("SOGoTimeZone");
					} catch (Exception nfe) {					
						o = json.get("TimeZone");
					}
					
				} catch (Exception pe) {
					log.error("Exception occured in getUserTimeZone(): " + pe.toString(), pe);
				}
				
				if (o != null) {
					tz = o.toString();
				}
			}
			
			rs.close();
			s.close();

		} catch (Exception e) {
			log.error("Exception occured in getUserTimeZone(): " + e.toString(), e);
		}

		if (tz == null) {
			log.info("No timezone defined in SOGo for user: " + context.getPrincipal().getUsername());
			return TimeZone.getTimeZone("GMT");
		}
		
		return TimeZone.getTimeZone(tz);
	}

    /**
     * Get time as seconds since the Epoc from a property.
     * The property must be in the form of yyyyMMddTHHmmssZ. If the input is not on this form
     * or an error occurs, <code>-1</code> will be returned. Any parse errors will be logged.
     *
     * @param prop The property to get the time from
     * @param defaultTZ Time zone to use if property has no time zone
     * @param log Application logger
     * @return The time, or <code>-1</code>
     */
    public static long getTimeFromProperty(PropertyWithTimeZone prop, TimeZone defaultTZ, FunambolLogger log) {
        if (prop == null) {
            log.debug("Property parameter is null");
            return -1;
        }

        String dateString = prop.getPropertyValueAsString();
        if (dateString == null || dateString.length() == 0) {
            log.debug("Property value is null or empty");
            return -1;
        }

        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        if (prop.getTimeZone() != null) {
            // Note! getTimeZone returns GMT if it can't parse the input
            formatter.setTimeZone(TimeZone.getTimeZone(prop.getTimeZone()));    
        } else {
            formatter.setTimeZone(defaultTZ);
        }

        try {
            return formatter.parse(dateString).getTime() / 1000;
        } catch (java.text.ParseException e) {
            log.warn(String.format("Parse exception from input '%s'", dateString), e);
            return -1;
        }
    }
	
    /**
     * 
     * By default, we return 1 so the event is opaque.
     * 
     * We consider the event as non-opaque if we have:
     * 
     * TRANSP:1
     * 
     * or
     * 
     * TRANSP:TRANSPARENT
     * 
     * @param e
     * @return
     */
    public static int getTransparency(com.funambol.common.pim.calendar.Event e) {
    	if (e.getTransp() != null) {
    		String s;
    		
    		s = e.getTransp().getPropertyValueAsString();
    		
    		if (s != null && (s.equalsIgnoreCase("1") || s.equalsIgnoreCase("TRANSPARENT")))
    			return 0;
    	}
    				
    	return 1;
    }

    /**
     * 
     * Get the main event category as String.
     * 
     * We consider that the main category is the first appearing in the comma separated list 
     * 
     * @param e
     * @return The main category name as String or NULL if no category property can be read
     */
    public static String getMainCategory(com.funambol.common.pim.calendar.Event e, FunambolLogger log) {
    	String s;
    	if (e.getCategories() != null) {

    		s = e.getCategories().getPropertyValueAsString();

    		return s.split(",")[0];
    	}
    	else {
    		return null;
    	}
    }
    
	/**
	 * 
	 * @param item
	 * @param log
	 * @return
	 */
	public static byte[] SIFCTovCard(byte[] item, SOGoSyncSource source, FunambolLogger log) {
		ContactToVcard conv;
		SIFCParser p;
		Contact c;
		
		log.info("About to convert (SIF-C -> vCard): " + new String(item));
		
		try {
			p = new SIFCParser(new ByteArrayInputStream(item));
			c = p.parse();
			
			conv = new ContactToVcard(null, source.getDeviceCharset());
			return conv.convert(c).getBytes();
						
		} catch (Exception e) {
			log.error("Exception occured in SIFCTovCard: " + e.toString(), e);
		}
		
		return null;
	}
	
	/**
	 * This method is used to convert SIF events and tasks to vCalendar components.
	 * It makes sure to use the proper converter in order to avoid cast exceptions
	 * in Funambol.
	 * 
	 * @param item
	 * @param log
	 * @return
	 */
	public static byte[] SIFTovCalendar(byte[] item, SOGoSyncSource source, FunambolLogger log) {
		com.funambol.common.pim.calendar.CalendarContent content;
		com.funambol.common.pim.calendar.Calendar c;
		VCalendarContentConverter conv;
		SIFCalendarParser p;
		StringBuffer sbuf;
		
		log.info("About to convert (SIF-{E,T} -> vCalendar): " + new String(item));
		
		try {		
			p = new SIFCalendarParser(new ByteArrayInputStream(item));
			c = p.parse();
			
			
			if ((content = c.getTask()) != null) {
				SOGoSanitizer.sanitizeSIFTask((Task)content);
			} else {
				content = c.getEvent();
			}
			
			conv = new VCalendarContentConverter(null, source.getDeviceCharset(), true);
					
			// We wrap our Event / Task inside a Calendar object. This is required
			// since addvEventSyncItem() and other methods expect this.
			sbuf = new StringBuffer();
			sbuf.append("BEGIN:VCALENDAR\r\n");
			sbuf.append("VERSION:1.0\r\n");
			sbuf.append(conv.cc2vcc(content, true).toString());
			sbuf.append("END:VCALENDAR\r\n");
			
			return sbuf.toString().getBytes();
			
		} catch (Exception e) {
			log.error("Exception occured in SIFTovCalendar: " + e.toString(), e);
		}
		
		return null;
	}
	
	public static void secureCalendarContent(CalendarContent cc, String tag, int classification) {
		String s;
		
		switch (classification) {
		case SOGoACLManager.SOGoACLPrivate:
			s = "(Private)";
			break;
		case SOGoACLManager.SOGoACLConfidential:
			s = "(Confidential)";
			break;
		default:
			s = "(Public)";
		}		
		cc.setSummary(new Property(tag + s));
		cc.setDescription(new Property(""));
		cc.setLocation(new Property(""));
		cc.setCategories(new Property(""));
		cc.setUid(new Property(""));
		cc.resetAttendees();
		cc.setDAlarm(new Property(""));
	}
	
	/**
	 * This method is used to convert a vCalendar object from v2 to v1.
	 * We might lose some information by doing so but we try our best 
	 * to not to. Here are the required transformations.
	 * 
	 * 1- removal of all VTIMEZONE information + UTC offset adjustments
	 * 2- start/end (or due) date adjustments if the connecting device
	 *    doesn't support UTC
	 * 3- if we get TZID in DTSTART/DTEND, convert DTSTART/DTEND to UTC
	 *    if our devices supports it, otherwise, leave it as is
	 *    
	 *    ---------------------------------------------------------------------------
	 *    Devices supports UTC	| Input				| TZ to use | Output
	 *    ----------------------|-------------------|-----------|--------------------
	 *    YES					| UTC				| none		| UTC
	 *    YES					| non-UTC			| pref. TZ	| UTC
	 *    YES					| non-UTC + TZID	| TZID		| UTC
	 *    NO					| UTC				| pref. TZ	| non-UTC
	 *    NO					| non-UTC			| none		| non-UTC
	 *    NO					| non-UTC + TZID	| none		| non-UTC
	 * 
	 * @param bytes
	 * @return
	 */
	public static byte[] vCalendarV2toV1(byte[] bytes, String tag, String key, boolean secure, int classification, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		String s;

		log.info("About to convert vCalendar (from v2 to v1): " + new String(bytes));
		s = "";

		try {
			VCalendarConverter converter;
            VComponentWriter writer;
			VCalendar v2, calendar;
			CalendarContent cc;
			ICalendarParser p;
			Calendar cal;

			// We need to proceed with the following steps
			// iCalendar -> VCalendar object -> Calendar object -> VCalendar v1.0 object -> string
			p = new ICalendarParser(new ByteArrayInputStream(SOGoSanitizer.sanitizevCalendarInput(bytes, key, log).getBytes()));
			v2 = p.ICalendar();
			
			converter = new VCalendarConverter(getUserTimeZone(source, context, log), source.getDeviceCharset(), false);
			cal = converter.vcalendar2calendar(v2);
		
			// HACK: Funambol (at least, v6.5-7.1) crashes in VCalendarContentConverter: cc2vcc
			//		 since it is trying to get this property's value while it might be null.
			SOGoSanitizer.sanitizeFunambolCalendar(cal);
			cc = cal.getCalendarContent();

			if (tag != null) {
				String summary;
				
				tag = "[" + tag + "] ";
				summary = cc.getSummary().getPropertyValueAsString();
				if (!summary.startsWith(tag)) {
					summary = tag + summary;
					cc.setSummary(new Property(summary));
				}
				
				if (secure) {
					secureCalendarContent(cc, tag, classification);
				}
			}
				
			// The Funambol client for BlackBerry devices is a real piece of crap.
			// It does NOT honor the X-FUNAMBOL-ALLDAY tag so we must do some magic
			// here to make it believe it's an all-day event. Otherwise, the all-day
			// event will span two days on the BlackBerry device.
			if (isBlackBerry(context) && cc.isAllDay()) {
				SimpleDateFormat formatter;
				java.util.Date d;
				String ss;
				
				// We can either parse 20120828 or 2012-08-28
				ss = cc.getDtStart().getPropertyValueAsString();
				d = null;
				
				try {
					formatter = new SimpleDateFormat("yyyy-MM-dd");
					d = formatter.parse(ss);
				} catch (Exception pe) {}
				
				if (d == null) {
					try {
						formatter = new SimpleDateFormat("yyyyMMdd");
						d = formatter.parse(ss);
					} catch (Exception pe) {}
				}
				
				if (d != null) {
					formatter = new SimpleDateFormat("yyyyMMdd");
					ss = formatter.format(d) + "T000000";
					cc.getDtStart().setPropertyValue(ss);
					
					ss = formatter.format(d) + "T235900";
					cc.getDtEnd().setPropertyValue(ss);

				}
			}
		
						
			// The boolean parameter triggers v1 vs. v2 conversion (v1 == true)
			calendar = converter.calendar2vcalendar(cal, true);
            copyCustomProperties(v2.getVCalendarContent(), calendar.getVCalendarContent());

            // Funambol loses alarm settings when converting to a Calendar object, see
            // http://forge.ow2.org/tracker/index.php?func=detail&aid=314860&group_id=96&atid=100096
            // Try to convert any valarm from v2 to aalarm.
            // This is implementation will probably fix 95% of the cases, but doesn't cover
            // multiple alarms or alarm times set relative to the end of the event
            // Also note that at least Symbian devices does not seem to support relative alarms
            VAlarm valarm = (VAlarm)v2.getVCalendarContent().getComponent("VALARM");
            log.info("VALARM is: " + valarm);
            if (valarm != null) {
                com.funambol.common.pim.model.Property trigger;
                
                // TRIGGER;VALUE=DURATION:-PT15M
                trigger = valarm.getProperty("TRIGGER");
                
                log.info("TRIGGER: " + trigger.getValue());   // -PT15
                                
                if (trigger.getParameter("VALUE") != null) {
                	String duration;
                	
                	duration = trigger.getParameter("VALUE").value;
                	
                	if (duration.equalsIgnoreCase("DURATION")) {
                        String value;

                        boolean negate;
                        int len, i, v;
                        char c;
                        
                        value = trigger.getValue(); //  -PT15
                        log.info("VALUE to parse: " + value);
                        len = value.length();
                        negate = false;
                        
                        // v is always in seconds
                        v = 0;
                        
                        // We parse :
                        //
                        // dur-value  = (["+"] / "-") "P" (dur-date / dur-time / dur-week)
                        //
                        // dur-date   = dur-day [dur-time]
                        // dur-time   = "T" (dur-hour / dur-minute / dur-second)
                        // dur-week   = 1*DIGIT "W"
                        // dur-hour   = 1*DIGIT "H" [dur-minute]
                        // dur-minute = 1*DIGIT "M" [dur-second]
                        // dur-second = 1*DIGIT "S"
                        // dur-day    = 1*DIGIT "D"
                        //
                        for (i = 0; i < len; i++) {
                        	c = value.charAt(i);
                       
                        	if (c == 'P' || c == 'p' || c == 'T' || c == 't') {
                        		log.info("Skipping " + c);
                        		continue;
                        	}
                        	
                        	if (c == '-') {
                        		negate = true;
                        		log.info("Negating...");
                        		continue;
                        	}
                        		
                        	if (Character.isDigit(c)) {
                        		int j, x;
                        		
                        		log.info("Digit found at " + i);

                        		for (j = i; j < len; j++) {
                                	c = value.charAt(j);
                        			if (!Character.isDigit(c))
                        				break;
                        		}
                        		
                        		log.info("End digit found at " + i);

                        		
                        		x = Integer.parseInt(value.substring(i, j));
                        		log.info("x = " + x);
                        		 
                        		// Char at j is either W, H, M, S or D
                        		switch (value.charAt(j)) {
                        		case 'W':
                        			v += x * (7*24*3600);
                        			break;
                        		case 'H':
                        			v += x * 3600;
                        			break;
                        		case 'M':
                        			v += x * 60;
                        			break;
                        		case 'S':
                        			v += x;
                        			break;
                        		case 'D':
                        		default:
                        			v += x * (24*3600);
                        			break;
                        			
                        		}
                        		log.info("v1 = " + v);

                        		i = j+1;
                        	}
                       } // for (...)
                        
                		log.info("v2 = " + v);

                       if (negate)
                    	   v = -v;
                       
                       log.info("v3 = " + v);


                       // Let's add this to our start time.. we'll support end time later
                       if (v != 0) {
                    	   SimpleDateFormat dateFormatter;
                    	   java.util.Date d;
                    	   
                    	   log.info("DTSTART: " + cc.getDtStart().getPropertyValue());
                    	   log.info("v = " + v);

                    	   try {
                    		   dateFormatter = new SimpleDateFormat(TimeUtils.PATTERN_UTC);
                    		   d = dateFormatter.parse(cc.getDtStart().getPropertyValueAsString());
                    		   d.setTime(d.getTime() + v*1000);
                    	   
                    		   calendar.getVCalendarContent().addProperty("AALARM", dateFormatter.format(d)); // DURATION
                    	   } catch (Exception e) {
                    		   log.error("Exception occured in vCalendarV2toV1(): " + e.toString(), e);
                    	   }
                       }
                	}
                			
                } 
            }
            
            // Write result
            writer = new VComponentWriter(VComponentWriter.NO_FOLDING);
			s = writer.toString(calendar);
			log.info(s);
		} catch (Exception e) {
			log.error("Exception occured in vCalendarV2toV1(): " + e.toString(), e);
			log.info("===== item content (" + key + ") =====");
			log.info(new String(bytes));
			log.info("======================================");
		}

		return s.getBytes();
	}

    /**
     * Copy vendor specific properties from a calendar object to another.
     * Funambol doesn't preserve these properties when converting to a Calendar object,
     * this method is intended to be used after a Calendar round-trip.
     *
     * @param from The calendar object to copy from
     * @param to   The calendar object to copy to
     */
    public static void copyCustomProperties(VCalendarContent from, VCalendarContent to) {
        for (Object o : from.getAllProperties()) {
            com.funambol.common.pim.model.Property p = (com.funambol.common.pim.model.Property)o;

            if (p.getName().startsWith("X-") && !p.getName().equals("X-FUNAMBOL-ALLDAY")) {
                to.addProperty(p);
            }
        }
    }

	/**
	 * 
	 * @param cc
	 * @return
	 */
	public static int getClassification(CalendarContent cc) {
		int classification, ac;
		
		classification = 0;
		if (cc.getAccessClass() != null) {
			ac = Integer.parseInt(cc.getAccessClass().getPropertyValueAsString());
			if (ac == SENSITIVITY_PRIVATE)
				classification = SOGO_SENSITIVITY_PRIVATE;
			if (ac == SENSITIVITY_CONFIDENTIAL)
				classification = SOGO_SENSITIVITY_CONFIDENTIAL;
		}
		return classification;
	}
	
	/**
	 * 
	 * @param conn
	 * @param table
	 * @param c_name
	 */
	public static void updateContentVersion(Connection conn, String table, String c_name) throws Exception {
		ResultSet rs;
		Statement s;
		int v;
		
		s = conn.createStatement();
		rs = s.executeQuery("SELECT c_version FROM " + table + " WHERE c_name = '" + c_name + "'");
		v = 1;
		
		if (rs.next()) {
			v = rs.getInt(1);
			v++;
		}
		
		rs.close();
		
		s.executeUpdate("UPDATE " + table + " SET c_version = " + v + " WHERE c_name = '" + c_name + "'");
		s.close();
	}
	
	/**
	 * 
	 * @param item
	 * @param log
	 * @return
	 */
	public static byte[] vCardToSIFC(byte[] item, SOGoSyncSource source, FunambolLogger log) {
		ContactToSIFC conv;
		VcardParser p;
		Contact c;
		
		try {
			p = new VcardParser(new ByteArrayInputStream(item), null, null);
			c = p.vCard();
			
			conv = new ContactToSIFC(null, source.getDeviceCharset());
			
			return conv.convert(c).getBytes();
		} catch (Exception e) {
			log.error("Exception occured in vCardToSIFC().", e);
		}
		
		return null;
	}
	
	public static boolean isIPhone(SyncContext context) {
		Sync4jDevice device;
		boolean b;

		b = false;
		
		device = context.getPrincipal().getDevice();

		if (device.getCapabilities().getDevInf().getMod() != null &&
				device.getCapabilities().getDevInf().getMod().equalsIgnoreCase("iPhone")) {
				b = true;
			}
		
		return b;
	}
	
	public static boolean isBlackBerry(SyncContext context) {
		Sync4jDevice device;
		boolean b;

		b = false;
		
		device = context.getPrincipal().getDevice();

		if (device.getCapabilities().getDevInf().getMan() != null &&
				device.getCapabilities().getDevInf().getMan().equalsIgnoreCase("Research In Motion")) {
				b = true;
			}
		
		return b;
	}
	
	/**
	 * This method is used to convert a vCard object from v3 to v2.1.
	 * 
	 * We use Funambol API to do this at its producer only supports v2.1 :) 
	 * 
	 * @param bytes
	 * @return
	 */
	public static byte[] vCardV3toV21(byte[] bytes, SOGoSyncSource source, SyncContext context, FunambolLogger log) {		
		try {
			ContactToVcard conv;
			VcardParser p;
			Contact c;
			String s;							
			int a, b;
			
			log.info("About to convert vCard (from v3 to v2.1): " + new String(bytes) + "\niPhone? " + isIPhone(context));
			s = SOGoSanitizer.sanitizevCardInput(bytes, context, log);
			s = s.replace("VERSION:3.0", "VERSION:2.1");
			
			// We replace properties with extra parameters
			if (!isIPhone(context))
				s = s.replace("EMAIL;TYPE=INTERNET,WORK", "EMAIL;INTERNET");      // BB or others
			else
				s = s.replace("EMAIL;TYPE=INTERNET,WORK", "EMAIL;INTERNET;WORK"); // iPhone OS
			s = s.replace("EMAIL;TYPE=INTERNET,HOME", "EMAIL;INTERNET;HOME");
			s = s.replace("TEL;TYPE=VOICE,WORK", "TEL;VOICE;WORK");
			s = s.replace("TEL;TYPE=VOICE,HOME", "TEL;VOICE;HOME");
			s = s.replace("TEL;TYPE=WORK,FAX", "TEL;FAX;WORK");
			
			// We replace properties with no extra parameters
			if (!isIPhone(context))
				s = s.replace("EMAIL;TYPE=WORK", "EMAIL;INTERNET");        // BB or others
			else
				s = s.replace("EMAIL;TYPE=WORK", "EMAIL;INTERNET;WORK");   // iPhone OS
			
			s = s.replace("EMAIL;TYPE=HOME", "EMAIL;INTERNET;HOME");
			s = s.replace("TEL;TYPE=WORK", "TEL;VOICE;WORK");
			s = s.replace("TEL;TYPE=HOME", "TEL;VOICE;HOME");
			s = s.replace("TEL;TYPE=CELL", "TEL;CELL");
			s = s.replace("TEL;TYPE=FAX", "TEL;FAX;WORK");
			s = s.replace("TEL;TYPE=PAGER", "TEL;PAGER");
			s = s.replace("ADR;TYPE=WORK", "ADR;WORK");
			s = s.replace("ADR;TYPE=HOME", "ADR;HOME");
			s = s.replace("URL;TYPE=WORK", "URL;WORK");
			s = s.replace("URL;TYPE=HOME", "URL;HOME");
			
			// We have to take care of the NOTE field which can have \r\n fields in it
			// iPhone sends this:   NOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:Line 1=0Aline 2=0Aline 3
			// BB sends this:       NOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:Line 1=0Aline 2=0Aline 3
			a = s.indexOf("\r\nNOTE:");
			
			if (a < 0)
				a = s.indexOf("\r\nNOTE;");
			
			if (a > 0) {
				b = s.indexOf(':', a);
				if (b > 0) {
					boolean encoded;
					int start, end;
					
					
					// We intelligently search the end of the NOTE field. It could be folded, ie., end with \r\n
					// but the line following stats with a  space or tab
					start = b;
					
					while (true) {
						end = s.indexOf("\r\n", start);
						if (end < 0)
							break;
						
						log.info("char end+2: |" + s.charAt(end+2) + "|");
						if (s.charAt(end+2) != ' ' && s.charAt(end+2) != '\t')
							break;
						
						start = start + (end-start+2);
					}
					
					if (end > 0) {
						String value;
						
						value = s.substring(b+1, end);
						
						encoded = (value.indexOf("\\r\\n") > 0);
						
						// We must replace \r\n with =0A
						if (encoded) {
							log.info("Initial value: " + value);
							
							value = value.replace("\\r\\n", "=0A");
							value = value.replace("\r\n", "");
							log.info("Converted value: " + value);

							s = s.substring(0, a) + "\r\nNOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:" + value + s.substring(end);
						}
					}
				}
				
			}
			
			
			log.info("Downgraded vCard: " + s); 

			// iPhone supports UTF-8
			if (isIPhone(context))
				return s.getBytes();
			
			// We must encode everything in QP
			p = new VcardParser(new ByteArrayInputStream(s.getBytes()), null, null);
			c = p.vCard();			
			conv = new ContactToVcard(TimeZone.getTimeZone("GMT"), source.getDeviceCharset());
			
			s = conv.convert(c);
			log.info("Encoded vCard: " + s);
			
			return s.getBytes();

		} catch (Exception e) {
			log.error("Exception occured in vCardV3toV21(): " + e.toString(), e);
		}
		
		return null;
	}
	
	/**
	 * 
	 * @param item
	 * @param log
	 * @return
	 */
	public static byte[] vEventToSIFE(byte[] item, String tag, String key, boolean secure, int classification, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		VCalendarContentConverter conv;
		VComponent c;
		ICalendarParser p;
		List l;
		int i;
		
		try {			
			p = new ICalendarParser(new ByteArrayInputStream(item));
			c = p.ICalendar();
			
			conv = new VCalendarContentConverter(SOGoUtilities.getUserTimeZone(source, context, log), source.getDeviceCharset(), true);
				
			l = c.getAllComponents();
			
			
			for (i = 0; i < l.size(); i++) {
				
				if (l.get(i) instanceof VCalendarContent) {
					com.funambol.common.pim.calendar.Calendar cal;
					VCalendarContent vcc;
					CalendarToSIFE ctse;
					
					vcc = (VCalendarContent)l.get(i);
					cal = new com.funambol.common.pim.calendar.Calendar(conv.vcc2cc(vcc, false));					
					
					if (tag != null) {
						com.funambol.common.pim.calendar.Event e;
						String s;
						
						e = cal.getEvent();
 						tag = "[" + tag + "] ";
						s = tag + e.getSummary().getPropertyValueAsString();
						e.setSummary(new Property(s));
						
						if (secure) {
							secureCalendarContent(e, tag, classification);
						}
					}
					
					ctse = new CalendarToSIFE(null, source.getDeviceCharset());
					return ctse.convert(cal).getBytes();
				}
			}	

		} catch (Exception e) {
			log.error("Exception occured in vEventToSIFE().", e);
			log.info("===== item content (" + key + ") =====");
			log.info(new String(item));
			log.info("======================================");
		}

		return null;
	}
	
	/**
	 * 
	 * @param item
	 * @param log
	 * @return
	 */
	public static byte[] vTodoToSIFT(byte[] item, String tag, String key, boolean secure, int classification, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		VCalendarContentConverter conv;
		ICalendarParser p;
		VComponent c;
		List l;
		int i;
		
		try {
			p = new ICalendarParser(new ByteArrayInputStream(item));
			c = p.ICalendar();
			conv = new VCalendarContentConverter(TimeZone.getTimeZone("GMT"), source.getDeviceCharset(), true);
		
			l = c.getAllComponents();
			for (i = 0; i < l.size(); i++) {
				
				if (l.get(i) instanceof VCalendarContent) {
					TaskToSIFT tts;
					Task t;
					
					t = (Task)conv.vcc2cc((VCalendarContent)l.get(i), true);
					
					if (tag != null) {
						String s;
						
						tag = "[" + tag + "] ";
						s = tag + t.getSummary().getPropertyValueAsString();
						t.setSummary(new Property(s));
						
						if (secure) {
							secureCalendarContent(t, tag, classification);
						}
					}
					
					tts = new TaskToSIFT(null, source.getDeviceCharset());
					SOGoSanitizer.sanitizeSIFTask(t);
					
					return tts.convert(t).getBytes();
				}
			}	
		} catch (Exception e) {
			log.error("Exception occured in vTodoToSIFT().", e);
			log.info("===== item content (" + key + ") =====");
			log.info(new String(item));
			log.info("======================================");
		}

		return null;
	}

	/**
	 * 
	 * @param content
	 * @param tag
	 * @param source
	 * @param context
	 * @param log
	 * @return
	 */
	public static String removeTagFromContent(String content, String tag, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		try {
			VCalendarConverter converter;
			VCalendar calendar;
			CalendarContent cc;
			ICalendarParser p;
			String summary;
			Calendar cal;
            VComponentWriter writer;

			p = new ICalendarParser(new ByteArrayInputStream(content.getBytes()));
			calendar = p.ICalendar();

			converter = new VCalendarConverter(getUserTimeZone(source, context, log), source.getDeviceCharset(), true);
			cal = converter.vcalendar2calendar(calendar);

			cc = cal.getCalendarContent();
			summary = cc.getSummary().getPropertyValueAsString();
			
			tag = ("[" + tag + "]").toLowerCase();
			if (summary.toLowerCase().startsWith(tag)) {
				summary = summary.substring(tag.length()+1);
			}
			
			cc.setSummary(new Property(summary));
			
			calendar = converter.calendar2vcalendar(cal, true);

            writer = new VComponentWriter(VComponentWriter.NO_FOLDING);
			return writer.toString(calendar);
		} catch (Exception e) {
			log.error("Exception occured in removeTagFromContent: " + e.toString(), e);
		}
		
		return content;
	}
}
