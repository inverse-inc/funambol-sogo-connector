/*
 * Copyright (C) 2007-2008 Inverse groupe conseil and Ludovic Marcotte
 * 
 * Author: Ludovic Marcotte <ludovic@inverse.ca>
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
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.text.SimpleDateFormat;
import java.util.*;

import com.funambol.common.pim.converter.VComponentWriter;
import com.funambol.common.pim.model.*;
import com.funambol.common.pim.calendar.Calendar;
import com.funambol.common.pim.calendar.Event;
import com.funambol.common.pim.converter.VCalendarConverter;
import com.funambol.common.pim.icalendar.ICalendarParser;
import com.funambol.common.pim.utility.TimeUtils;
import com.funambol.framework.engine.SyncItem;
import com.funambol.framework.engine.SyncItemState;
import com.funambol.framework.engine.source.SyncContext;
import com.funambol.framework.logging.FunambolLogger;
import com.funambol.framework.engine.source.SyncSourceException;


public class SOGoEventUtilities {
	
	/**
	 * This method is used to add a new vEvent object in the SOGo database.
     * The if the event is in vCalendar format, it is converted to vCalendar before it is saved to the database.
	 *
	 * @param item The item to add
     * @param source Sync source object
     * @param context Sync context object
	 * @param log Logger object
	 * @return A SyncItem with updated status
	 */	 
	public static SyncItem addvEventSyncItem(SyncItem item, SOGoSyncSource source, SyncContext context, FunambolLogger log)  {
		try {
			String c_content, c_name, c_location, c_cycleinfo, c_title, location, tag;
			PreparedStatement s;
			TimeZone userTZ;
			Event event;
            CalendarData data;

			int c_iscycle, a, b, c_classification, c_isopaque;
			long s_date, e_date, created, modified;
						
            userTZ = SOGoUtilities.getUserTimeZone(source, context, log);
            data = new CalendarData(item, userTZ, source.getDeviceCharset());
			c_content = data.getContent();
						
			event = data.getCalendar().getEvent();
			c_name = SOGoKey.encodeString(item.getKey().getKeyAsString());			
			c_classification = SOGoUtilities.getClassification(event);
			c_isopaque = SOGoUtilities.getTransparency(event);
			c_cycleinfo = null;
			c_iscycle = 0;

			s_date = getStartDate(event, source, context, log);
			e_date = getEndDate(event, source, context, log);
			
			if (event.getLocation() != null) {
				c_location = event.getLocation().getPropertyValueAsString();
			} else {
				c_location = null;
			}

			// We check if there's a recurrence rule.
			if (event.getRecurrencePattern() != null) {							
				c_cycleinfo = "{rules = (\"" + data.getVcalendar().getVCalendarContent().getProperty("RRULE").getValue() + "\"); }";
				c_iscycle = 1;
			}
			
			c_title = event.getSummary().getPropertyValueAsString().trim();

			// If the title is tagged, we untag it and we find the relevant target calendar
			a = c_title.indexOf('[');
			b = c_title.indexOf(']');
			location = null;
			tag = null;
			
			if (a == 0 && b > 0) {				
				tag = c_title.substring(1, b).toLowerCase();
				c_title = c_title.substring(b+2);
								
				if (tag.length() > 0) {
					location = source.getLocationForTag(tag);
					log.info("found target location: " + location);
				} else {
					tag = null;
				}
			}
			
			if (tag == null || location == null)
				location = source.getCalendarTable();
			
			// We check if we can add items from the collection (ObjectCreator)
			if (tag != null && source.getCollectionForTag(tag) != null) {
				int roles;
				
				roles = SOGoACLManager.getACL(source, context, log, location, source.getCollectionForTag(tag), 0);
				
				if (roles != SOGoACLManager.SOGoACLOwner && !SOGoACLManager.hasACL(roles, SOGoACLManager.SOGoACLObjectCreator))
					throw new SyncSourceException("No access rights on " + location + " - ObjectCreator is needed");
				
				c_content = SOGoUtilities.removeTagFromContent(c_content, tag, source, context, log);
			}

            // Get creation and modification time for event
            created = SOGoUtilities.getTimeFromProperty(event.getCreated(), userTZ, log);
            modified = SOGoUtilities.getTimeFromProperty(event.getLastModified(), userTZ, log);

            // Use event timestamp if modified isn't set
            if (modified == -1) {
                modified = item.getTimestamp().getTime() / 1000;
            }

            // Use modifed as fallback for created
            if (created == -1) {
                created = modified;
            }

			// We insert into our normal table
			s = source.getDBConnection().prepareStatement("INSERT INTO " + location + " (c_name, c_content, c_creationdate, c_lastmodified, c_version) VALUES (?, ?, ?, ?, ?)");
			s.setString(1, c_name);
			s.setString(2, c_content);
			s.setLong(3, created);
			s.setLong(4, modified);
			s.setInt(5, 0);
			s.executeUpdate();
			s.close();

			// We now update the quick table
			s = source.getDBConnection().prepareStatement("INSERT INTO " + location + "_quick" + " (c_name, c_uid, c_startdate, c_enddate, c_title, c_isallday, c_classification, c_status, c_priority, c_location, c_partmails, c_partstates, c_component, c_isopaque, c_iscycle, c_cycleinfo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
			s.setString(1, c_name);		  // c_cname
			s.setString(2, c_name); 	  // c_uid
			s.setLong(3, s_date);		  // c_startdate
			s.setLong(4, e_date);		  // c_endate
			s.setString(5, c_title);	  // c_title
			s.setInt(6, event.isAllDay() ? 1 : 0); 	// c_isallday
			s.setInt(7, c_classification);// c_classification  
			s.setInt(8, 0);				  // c_status
			s.setInt(9, 0);  		      // c_priority
			s.setString(10, c_location);  // c_location
			s.setString(11, "");		  // c_partmails
			s.setString(12, "");		  // c_partstates
			s.setString(13, "vevent");    // c_component
			s.setInt(14, c_isopaque);	  // c_isopaque
			s.setInt(15, c_iscycle);      // c_iscycle
			s.setString(16, c_cycleinfo); // c_cycleinfo
			s.executeUpdate();
			s.close();
			
			source.getDBConnection().commit();

			item.setState(SyncItemState.SYNCHRONIZED);		
		} catch (Exception e) {
			log.error("Exception occured in addvEventSyncItem: " + e.toString(), e);
		}

		return item;
	}
	
	/**
	 * 
	 * @param event
	 * @param source
	 * @param context
	 * @param log
	 * @return
	 */
	public static long getEndDate(Event event, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		long e_date;
		
		try {
			SimpleDateFormat formatter;
			String str_end;
			
			str_end = (String)event.getDtEnd().getPropertyValue();

			// NOTE: This is since SyncJe sends us all-day events using a date-time
			//       We'll to check if other clients do that.
			if (event.isAllDay()) {
				str_end = TimeUtils.convertDateFromInDayFormat(str_end, "000000", true);
			}
			
			if (str_end == null || str_end.length() == 0) return 0;

			formatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");

			// For devices not supporting UTC, we must adjust the start/end date
			// based on the user's preferred time zone. Added the 'Z' hack since
			// SyncJe v2.37 reports it doesn't support UTC while it sends DATE-TIME
			// values in UTC.
			//if ((!SOGoUtilities.getDeviceUTC(context) && !str_end.endsWith("Z")) || event.isAllDay()) {
			if (event.isAllDay()) {
				formatter.setTimeZone(SOGoUtilities.getUserTimeZone(source, context, log));
			} else {						
				formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
			}

			e_date = (long)(formatter.parse(str_end).getTime()/1000);
			
		} catch (Exception e) {
			log.error("Exception occured in getEndDate: " + e.toString(), e);
			e_date = 0;
		}
		
		return e_date;
	}
	
	/**
	 * 
	 * @param event
	 * @param source
	 * @param context
	 * @param log
	 * @return
	 */
	public static long getStartDate(Event event, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		long s_date;
		
		try {
			SimpleDateFormat formatter;
			String str_start;
			
			str_start = (String)event.getDtStart().getPropertyValue();

			// NOTE: This is since SyncJe sends us all-day events using a date-time
			//       We'll to check if other clients do that.
			if (event.isAllDay()) {
				str_start = TimeUtils.convertDateFromInDayFormat(str_start, "000000", true);
			}
			
			if (str_start == null || str_start.length() == 0) return 0;
			
			formatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");

			// For devices not supporting UTC, we must adjust the start/end date
			// based on the user's preferred time zone. Added the 'Z' hack since
			// SyncJe v2.37 reports it doesn't support UTC while it sends DATE-TIME
			// values in UTC.
			//if ((!SOGoUtilities.getDeviceUTC(context) && !str_start.endsWith("Z")) || event.isAllDay()) {
			if (event.isAllDay()) {
				s_date = (long)(formatter.parse(str_start).getTime()/1000);
			} else {
			//formatter.setTimeZone(SOGoUtilities.getUserTimeZone(source, context, log));
			//} else {						
				formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
			}

			s_date = (long)(formatter.parse(str_start).getTime()/1000);
			log.info("DATE 2: " + s_date +  " tz: " + formatter.getTimeZone().toString());

		} catch (Exception e) {
			log.error("Exception occured in getStartDate: " + e.toString(), e);
			s_date = 0;
		}
		
		return s_date;
	}
	
	/**
	 * 
	 * @param item
	 * @param tag
	 * @param collection
	 * @param source
	 * @param context
	 * @param log
	 * @return
	 */
	public static SyncItem updatevEventSyncItem(SyncItem item, String tag, String collection, SOGoSyncSource source, SyncContext context, FunambolLogger log) throws SyncSourceException {
		try {
			String c_content, c_name, c_title, c_location, c_cycleinfo;
			PreparedStatement s;
			TimeZone userTZ;
			Event event;
            CalendarData data;

			int c_iscycle, c_classification, c_isopaque;
			long s_date, e_date, modified;

			boolean doMerge = true;
			
            userTZ = SOGoUtilities.getUserTimeZone(source, context, log);
            data = new CalendarData(item, userTZ, source.getDeviceCharset());
			c_content = data.getContent();
            event = data.getCalendar().getEvent();
            c_title = event.getSummary().getPropertyValueAsString();
			c_isopaque = SOGoUtilities.getTransparency(event);

			// If the event is tagged, we must untag it and check the ACLs.
			if (tag != null && source.getCollectionForTag(tag) != null) {
				int role, roles;
				
				// We check the ACLs in order to see if we can modify that particular event.
				// We first load its classification from the database.
				role = SOGoACLManager.getRoleFromClassification(item, source, context, log, collection);	
				roles = SOGoACLManager.getACL(source, context, log, collection, source.getCollectionForTag(tag), role);
				role = role | SOGoACLManager.SOGoACLModifier;
				
				log.info("role from class: " + role + " roles: " + roles);
				
				if (roles == SOGoACLManager.SOGoACLNone ||
					(roles != SOGoACLManager.SOGoACLOwner && !SOGoACLManager.hasACL(roles, role)))
					throw new SyncSourceException("No access rights on " + collection + " - Modifier is needed");
				
				c_content = SOGoUtilities.removeTagFromContent(c_content, tag, source, context, log);
                c_title = c_title.substring(tag.length() + 3);
                event.getSummary().setPropertyValue(c_title);
			}
			
			c_classification = SOGoUtilities.getClassification(event);
			c_name = SOGoKey.encodeString(item.getKey().getKeyAsString());					
			c_cycleinfo = null;
			c_iscycle = 0;

			s_date = getStartDate(event, source, context, log);
			e_date = getEndDate(event, source, context, log);

			if (event.getLocation() != null) {
				c_location = event.getLocation().getPropertyValueAsString();
			} else {
				c_location = null;
			}

			// We check if there's a recurrence rule.
			if (event.getRecurrencePattern() != null) {							
				c_cycleinfo = "{rules = (\"" + data.getVcalendar().getVCalendarContent().getProperty("RRULE").getValue() + "\"); }";
				c_iscycle = 1;
			}
			
            // Get modification time from the event. We do this PRIOR the merge as LAST-MODIFIED will be pulled
			// back from the original event and used instead of the one coming from the changed event.
            modified = SOGoUtilities.getTimeFromProperty(event.getLastModified(), userTZ, log);

            // We fallback to the item's creation date
            if (modified == -1) {
                modified = item.getTimestamp().getTime() / 1000;
            }

			if (doMerge) {
				Event originalEvent;
				ResultSet rs;
				String merged_content;

				s = source.getDBConnection().prepareStatement("SELECT c_content FROM " + collection + " WHERE c_name = ?");
				s.setString(1, c_name);
				rs = s.executeQuery();
				
				originalEvent = null;
				merged_content = null;

				if (rs.next()) {
					List<com.funambol.common.pim.model.Property> l;
					VCalendarConverter converter;
					VCalendar calendar;
					ICalendarParser p;
					Calendar cal;
					VEvent v;
                    VComponentWriter writer;
					int i;
					
					p = new ICalendarParser(new ByteArrayInputStream(rs.getString(1).getBytes()));
					calendar = p.ICalendar();
					
					// We save our attendees' list as Funambol will stupidly loose it
					// during conversion. We'll restore it right at the end.
					l = calendar.getFirstVEvent().getProperties("ATTENDEE");

					converter = new VCalendarConverter(SOGoUtilities.getUserTimeZone(source, context, log), source.getDeviceCharset(), true);
					cal = converter.vcalendar2calendar(calendar);
					originalEvent = cal.getEvent();
					event.merge(originalEvent);
					cal.setEvent(originalEvent);

					calendar = converter.calendar2vcalendar(cal, false);

					v = calendar.getFirstVEvent();
					calendar.delComponent(v);

					for (i = 0; i < l.size(); i++) {
						v.addProperty((com.funambol.common.pim.model.Property)l.get(i));
					}
					
					calendar.addEvent(v);
                    writer = new VComponentWriter(VComponentWriter.NO_FOLDING);
					merged_content = writer.toString(calendar);
				}

				if (originalEvent != null) {
					c_content = merged_content;
				}

			} // if (doMerge) ...
			
			// We insert into our normal table
			s = source.getDBConnection().prepareStatement("UPDATE " + collection + " SET c_content = ?, c_lastmodified = ? WHERE c_name = ?");
			s.setString(1, c_content);
			s.setLong(2, modified);
			s.setString(3, c_name);
			s.executeUpdate();
			s.close();

			// We now update the quick table
			// FIXME consider other values
			s = source.getDBConnection().prepareStatement("UPDATE " + collection + "_quick" + " SET c_startdate = ?, c_enddate = ?, c_title = ?, c_isallday = ?, c_location = ?, c_iscycle = ?, c_cycleinfo = ?, c_classification = ?, c_isopaque = ? WHERE c_name = ?");
			s.setLong(1, s_date);					// c_startdate
			s.setLong(2, e_date);					// c_endate
			s.setString(3, c_title);				// c_title
			s.setInt(4, event.isAllDay() ? 1 : 0); 	// c_isallday
			s.setString(5, c_location); 			// c_location
			s.setInt(6, c_iscycle);                 // c_iscycle
			s.setString(7, c_cycleinfo);			// c_cycleinfo
			s.setInt(8, c_classification);			// c_classification
			s.setInt(9, c_isopaque);				// c_isopaque
			s.setString(10, c_name);
			s.executeUpdate();
			s.close();

			SOGoUtilities.updateContentVersion(source.getDBConnection(), collection, c_name);

			source.getDBConnection().commit();
			
			item.setState(SyncItemState.UPDATED);
		} catch (Exception e) {
			log.error("Exception occured in updatevEventSyncItem: " + e.toString(), e);
		}
		return item;
	}
}
