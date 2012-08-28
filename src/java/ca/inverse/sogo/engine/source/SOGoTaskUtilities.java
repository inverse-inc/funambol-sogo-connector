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

import java.io.ByteArrayInputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

import com.funambol.common.pim.calendar.Calendar;
import com.funambol.common.pim.calendar.Task;
import com.funambol.common.pim.converter.VCalendarConverter;
import com.funambol.common.pim.converter.VComponentWriter;
import com.funambol.common.pim.icalendar.ICalendarParser;
import com.funambol.common.pim.model.VCalendar;
import com.funambol.common.pim.model.VTodo;
import com.funambol.common.pim.utility.TimeUtils;
import com.funambol.framework.engine.SyncItem;
import com.funambol.framework.engine.SyncItemState;
import com.funambol.framework.engine.source.SyncContext;
import com.funambol.framework.engine.source.SyncSourceException;
import com.funambol.framework.logging.FunambolLogger;

public class SOGoTaskUtilities {
	/**
	 * 
	 * @param item
	 * @param connection
	 * @param username
	 * @param log
	 * @return
	 */
	public static SyncItem addvTodoSyncItem(SyncItem item, SOGoSyncSource source, SyncContext context, FunambolLogger log) throws SyncSourceException {
		try {
			String c_content, c_name, c_title, c_location, location, tag;
			PreparedStatement s;
			TimeZone userTZ;
			Task task;
            CalendarData data;

			long s_date, due_date, created, modified;
			int a, b, c_classification, c_status, c_priority;

            userTZ = SOGoUtilities.getUserTimeZone(source, context, log);
            data = new CalendarData(item, userTZ, source.getDeviceCharset());
			c_content = data.getContent();
			task = data.getCalendar().getTask();
			c_name = SOGoKey.encodeString(item.getKey().getKeyAsString());
			c_title = task.getSummary().getPropertyValueAsString();
			c_classification = SOGoUtilities.getClassification(task);

			if (task.getComplete().getPropertyValue() == null) {
                c_status = 0;
            } else {
            	try 
            	{
            		// Not sure why a value of "true" appears, but it will not
            		// be convertable to int
            		c_status = Integer.parseInt(task.getComplete().getPropertyValueAsString());
            	} catch (NumberFormatException e) {
            		if ("true".equals(task.getComplete().getPropertyValueAsString())) {
            			c_status = 1;
            		} else {
            			c_status = 0;
            		}
            	}
            }
            
            if (task.getImportance() == null || task.getImportance().getPropertyValue() == null) {
                c_priority = 0;
            } else {
                c_priority = Integer.parseInt(task.getImportance().getPropertyValueAsString());
            }

			if (task.getLocation() != null) {
				c_location = task.getLocation().getPropertyValueAsString();
			} else {
				c_location = null;
			}

			// We also check for the length of the DTSTART / DTEND to avoid empty values
			s_date = getStartDate(task, source, context, log);
			due_date = getDueDate(task, source, context, log);
			
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

            // Get creation and modification time for task
            created = SOGoUtilities.getTimeFromProperty(task.getCreated(), userTZ, log);
            modified = SOGoUtilities.getTimeFromProperty(task.getLastModified(), userTZ, log);

            // Use item timestamp if modified isn't set
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
			s = source.getDBConnection().prepareStatement("INSERT INTO " + location + "_quick" + " (c_name, c_uid, c_startdate, c_enddate, c_title, c_isallday, c_iscycle, c_classification, c_status, c_priority, c_location, c_partmails, c_partstates, c_component, c_isopaque) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
			s.setString(1, c_name);		   // c_cname
			s.setString(2, c_name); 	   // c_uid
			s.setLong(3, s_date);		   // c_startdate
			s.setLong(4, due_date);		   // c_endate
			s.setString(5, c_title);	   // c_title
			s.setInt(6, 0); 			   // c_isallday
			s.setInt(7, 0);				   // is_cycle (must be at least 0 so the event appears in SOGo)
			s.setInt(8, c_classification); // c_classification  
			s.setInt(9, c_status);		   // c_status
			s.setInt(10, c_priority);      // c_priority
			s.setString(11, c_location);   // c_location
			s.setString(12, "");		   // c_partmails
			s.setString(13, "");		   // c_partstates
			s.setString(14, "vtodo");      // c_component
			s.setInt(15, 0);	    	   // c_isopaque
			s.executeUpdate();
			s.close();

			source.getDBConnection().commit();
			
			item.setState(SyncItemState.SYNCHRONIZED);
		} catch (Exception e) {
			log.error("Exception occured in addvTodoSyncItem: " + e.toString(), e);
		}
		return item;
	}
	
	/**
	 * 
	 * @param task
	 * @param formatter
	 * @param log
	 * @return
	 */
	public static long getDueDate(Task task, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		SimpleDateFormat formatter;
		long due_date;
		
		formatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");

		// For devices not supporting UTC, we must adjust the start/end date
		// based on the user's preferred time zone.
		//if (!SOGoUtilities.getDeviceUTC(context)) {		
		formatter.setTimeZone(SOGoUtilities.getUserTimeZone(source, context, log));
		//} else {						
		//	formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
		//}
		
		if (task.getDueDate() != null && task.getDueDate().getPropertyValue() != null && ((String)task.getDueDate().getPropertyValue()).length() > 0) {
			log.info("due: " + task.getDueDate().getPropertyValue().toString());
			try {
				due_date = (long)(formatter.parse((String)task.getDueDate().getPropertyValue()).getTime()/1000);			
			} catch (Exception e) {
				try {
					due_date = formatter.parse(TimeUtils.convertDateFromInDayFormat((String)task.getDueDate().getPropertyValue(),"000000", true)).getTime();
					due_date = due_date / 1000;
				} catch (Exception ee) {
					log.error("Exception occured in getDueDate: " + e.toString(), e);
					due_date = 0;
				}
			}
		} else {
			due_date = 0;
		}
		
		return due_date;
	}
	
	/**
	 * 
	 * @param task
	 * @param formatter
	 * @param log
	 * @return
	 */
	public static long getStartDate(Task task, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		SimpleDateFormat formatter;
		long s_date;
		
		formatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");

		// For devices not supporting UTC, we must adjust the start/end date
		// based on the user's preferred time zone.
		//if (!SOGoUtilities.getDeviceUTC(context)) {		
		formatter.setTimeZone(SOGoUtilities.getUserTimeZone(source, context, log));
		//} else {						
		//	formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
		//}
		
		if (task.getDtStart() != null && task.getDtStart().getPropertyValue() != null && ((String)task.getDtStart().getPropertyValue()).length() > 0) {
			log.info("startdate: " + task.getDtStart().getPropertyValue().toString() + " class = " + task.getDtStart().getPropertyValue().getClass().toString());

			try {
				s_date = (long)(formatter.parse((String)task.getDtStart().getPropertyValue()).getTime()/1000);
			} catch (Exception e) {
				// We might get something like: DUE:2008-04-19
				// This will break our formatter
				try {
					s_date = formatter.parse(TimeUtils.convertDateFromInDayFormat((String)task.getDtStart().getPropertyValue(),"000000", true)).getTime();
					s_date = s_date / 1000;
				} catch (Exception ee) {
					log.error("Exception occured in getStartDate: " + e.toString(), e);
					s_date = 0;
				}
			}
		} else {
			s_date = 0;
		}
		
		return s_date;
	}
	
	/**
	 * 
	 * @param item
	 * @param connection
	 * @param username
	 * @param log
	 * @return
	 */
	public static SyncItem updatevTodoSyncItem(SyncItem item, String tag, String collection, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		try {
			String c_content, c_name, c_title, c_location;
			SimpleDateFormat formatter;
			PreparedStatement s;
			TimeZone userTZ;
			Task task;
            CalendarData data;

			long s_date, due_date, modified;
			int c_classification, c_status, c_priority;
			
			boolean doMerge = true;
			
            userTZ = SOGoUtilities.getUserTimeZone(source, context, log);
            data = new CalendarData(item, userTZ, source.getDeviceCharset());
			c_content = data.getContent();
            task = data.getCalendar().getTask();
			c_title = task.getSummary().getPropertyValueAsString();

			// If the event is tagged, we must untag it and check ACLs.
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
                task.getSummary().setPropertyValue(c_title);
			}
			
			c_name = SOGoKey.encodeString(item.getKey().getKeyAsString());
			c_classification = SOGoUtilities.getClassification(task);

            if (task.getComplete().getPropertyValue() == null) {
                c_status = 0;
            } else {
            	try {
            		c_status = Integer.parseInt(task.getComplete().getPropertyValueAsString());
            	} catch (NumberFormatException e) {
            		if ("true".equals(task.getComplete().getPropertyValueAsString())) {
            			c_status = 1;
            		} else {
            			c_status = 0;
            		}
            	}
            }

            if (task.getImportance() == null || task.getImportance().getPropertyValue() == null) {
                c_priority = 0;
            } else {
                c_priority = Integer.parseInt(task.getImportance().getPropertyValueAsString());
            }

			if (task.getLocation() != null) {
				c_location = task.getLocation().getPropertyValueAsString();
			} else {
				c_location = null;
			}

			formatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");

			// For devices not supporting UTC, we must adjust the start/end date
			// based on the user's preferred time zone.
			if (!SOGoUtilities.getDeviceUTC(context)) {		
				formatter.setTimeZone(SOGoUtilities.getUserTimeZone(source, context, log));
			} else {						
				formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
			}
			
			// We also check for the length of the DTSTART / DTEND to avoid empty values
			s_date = getStartDate(task, source, context, log);
			due_date = getDueDate(task, source, context, log);
			
            // Get modification time from the task. We do this PRIOR the merge as LAST-MODIFIED will be pulled
			// back from the original task and used instead of the one coming from the changed task.
			modified = SOGoUtilities.getTimeFromProperty(task.getLastModified(), userTZ, log);

            // We fallback to the item's creation date
            if (modified == -1) {
                modified = item.getTimestamp().getTime() / 1000;
            }

			if (doMerge) {
				Task originalTask;
				ResultSet rs;
				String merged_content;

				s = source.getDBConnection().prepareStatement("SELECT c_content FROM " + collection + " WHERE c_name = ?");
				s.setString(1, c_name);
				rs = s.executeQuery();
				
				originalTask = null;
				merged_content = null;

				if (rs.next()) {
					VCalendarConverter converter;
					VCalendar calendar;
					ICalendarParser p;
					Calendar cal;
					VTodo v;
                    VComponentWriter writer;
					
					p = new ICalendarParser(new ByteArrayInputStream(rs.getString(1).getBytes()));
					calendar = p.ICalendar();
					

					converter = new VCalendarConverter(SOGoUtilities.getUserTimeZone(source, context, log), source.getDeviceCharset(), true);
					cal = converter.vcalendar2calendar(calendar);
                    SOGoSanitizer.sanitizeFunambolCalendar(cal);
					originalTask = cal.getTask();
					task.merge(originalTask);
					cal.setTask(originalTask);

					calendar = converter.calendar2vcalendar(cal, false);

					v = calendar.getFirstVTodo();
					calendar.delComponent(v);
					
					calendar.addTodo(v);

                    writer = new VComponentWriter(VComponentWriter.NO_FOLDING);
					merged_content = writer.toString(calendar);
				}

				if (originalTask != null) {
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
			s = source.getDBConnection().prepareStatement("UPDATE " + collection + "_quick" + " SET c_startdate = ?, c_enddate = ?, c_title = ?, c_location = ?, c_classification = ?, c_status = ?, c_priority = ? WHERE c_name = ?");
			s.setLong(1, s_date);		   // c_startdate
			s.setLong(2, due_date);		   // c_endate
			s.setString(3, c_title);	   // c_title
			s.setString(4, c_location);    // c_location
			s.setInt(5, c_classification); // c_classification
            s.setInt(6, c_status);         // c_status
            s.setInt(7, c_priority);       // c_priority
			s.setString(8, c_name);
			s.executeUpdate();
			s.close();

			SOGoUtilities.updateContentVersion(source.getDBConnection(), collection, c_name);

			source.getDBConnection().commit();
			
			item.setState(SyncItemState.UPDATED);

		} catch (Exception e) {
			log.error("Exception occured in updatevTodoSyncItem: " + e.toString(), e);
		}
		return item;
	}
}
