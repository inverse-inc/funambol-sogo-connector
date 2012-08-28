/*
 * Copyright (C) 2007-2008 Inverse inc. and Ludovic Marcotte
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

//
// Possible values synchronization values:
//												SOGOSyncSource Type 	Internal Type
//		Notes 		 -> text/x-s4j-sifn			<not supported>			<not supported>
//					 -> text/x-vnote			<not supported>			<not supported>
//
//		Contacts 	 -> text/x-s4j-sifc			SOGO_CONTACT			X_S4J_SIFC
//					 -> text/x-vcard			SOGO_CONTACT			VCARD_VERSION_21 / VCARD_VERSION_30
//
//		Events		 -> text/x-s4j-sife			SOGO_EVENT				X_S4J_SIFE
//					 -> text/x-vevent			SOGO_EVENT				VCALENDAR_VERSION_10 / VCALENDAR_VERSION_20
//
//		Tasks		 -> text/x-s4j-sift			SOGO_TODO				X_S4J_SIFT
//					 -> text/x-vtodo			SOGO_TODO				VCALENDAR_VERSION_10 / VCALENDAR_VERSION_20
//
// SOGo can store natively text/x-vcard, text/x-vevent and text/x-vtodo data but all
// SIF data must be properly converted to text/x-... before being added to SOGo.
//
// We might also have to convert, sometimes, vCard from v3.0 to v2.1 and vCalendar
// from v2.0 to v1.0 if the connecting device doesn't support the most recent standards.
//
import java.io.Serializable;
import java.sql.*;
import java.util.*;

import com.funambol.framework.logging.FunambolLogger;
import com.funambol.framework.logging.FunambolLoggerFactory;
import com.funambol.framework.core.AlertCode;
import com.funambol.framework.core.Constants;
import com.funambol.framework.engine.source.*;
import com.funambol.framework.engine.*;
import com.funambol.framework.tools.Base64;
import com.funambol.framework.tools.beans.LazyInitBean;
import com.funambol.common.pim.contact.Contact;
import com.funambol.common.pim.contact.Email;
import com.funambol.common.pim.calendar.*;

/**
 */
public class SOGoSyncSource extends AbstractSyncSource implements SyncSource, Serializable, LazyInitBean {
	
	// Constants for serialization	
	private static final long serialVersionUID = 1;
	
	// Constants for SOGoSyncSource types
	// The SOGO_CAL type is for Symbian-based devices.
	// They can use the same sync source to sync both tasks
	// and events.
	public static final int SOGO_CONTACT = 1;
	public static final int SOGO_EVENT = 2;
	public static final int SOGO_TODO = 3;
	public static final int SOGO_CAL = 4;
	
	// Constants for internal types
	public static final int VCALENDAR_VERSION_10 = 1;
	public static final int VCALENDAR_VERSION_20 = 2;
	public static final int VCARD_VERSION_21 = 3;
	public static final int VCARD_VERSION_30 = 4;
	public static final int X_S4J_SIFC = 5;
	public static final int X_S4J_SIFE = 6;
	public static final int X_S4J_SIFT = 7;

	// Private ivars
	private Connection _connection = null;
	private SyncContext _context = null;
	private FunambolLogger _log = null;
	private String _username = null;
	private String _password = null;
	private String _charset = null;
	private String _url = null;
	private int _source_type;
	
	
	// Private ivars used on a per-user basis to
	// obtain the information from SOGo
	private String _calendar_table;
	private String _contact_table;
	private String _contact_quick_table;
	
	// Private ivars used for tag-based calendar sharing
	@SuppressWarnings(value={"unchecked"})
	private HashMap _sync_tags;
	
	@SuppressWarnings(value={"unchecked"})
	private HashMap _sync_tags_location;
	
	
	/**
	 * This is the default constructor to build an AbstractSyncSource instance.
	 */
	public SOGoSyncSource() {
	}

	/*
	 * @see com.funambol.framework.tools.beans.LazyInitBean#init()
	 */
	public void init() {
		SyncSourceInfo info;
		String itemType;
		
		_log = FunambolLoggerFactory.getLogger("funambol.sogo");
		info = getInfo();

		_log.info("Source URI: " + getSourceURI());
	
		// We set our preferred type. This is actually the values
		// specified when configuring the sync source from the
		// Funambol Administration Tool.
		itemType = info.getPreferredType().getType();
		
		if (itemType.equalsIgnoreCase("text/x-vevent")) {
			_source_type = SOGO_EVENT;
		} else if (itemType.equalsIgnoreCase("text/x-vtodo")) {
			_source_type = SOGO_TODO;
		} else if (itemType.equalsIgnoreCase("text/x-vcal")) {
			_source_type = SOGO_CAL;
		} else {
			_source_type = SOGO_CONTACT;
		}
		
		// We adjust our supported Content-Type:s based on the source type
		// The Synthesis SyncML client needs a properly defined version number
		// for each supported Content-Type. Otherwise, clients will generate a
		// 10415 error message.
		if (_source_type == SOGO_CONTACT) {
			info.setSupportedTypes(new ContentType[] {
					new ContentType("text/x-vcard", "2.1"),
					new ContentType("text/vcard", "3.0") }
			);
		} else if (_source_type == SOGO_CAL) {
			// FIXME: alge: Symbian combined event/task sync 
			info.setSupportedTypes(new ContentType[] {
					new ContentType("text/x-vcalendar", "1.0"),
					new ContentType("text/x-vtodo", "1.0"),
					new ContentType("text/vtodo", "2.0") ,
					new ContentType("text/x-vevent", "1.0"),
					new ContentType("text/vevent", "2.0") }
			);
		} else {
			info.setSupportedTypes(new ContentType[] {
					new ContentType("text/x-vcalendar", "1.0") }
			);
		}
		
		_log.info("Done! Internal item type: " + _source_type + " (" + itemType + ")");
	}

	/**
	 * 
	 */
	public void beginSync(SyncContext context) throws SyncSourceException {

		String userid;
	
		_log.info("In beginSync()...");
		
		// We first reset everything
		super.beginSync(context);

		try  {
			userid = ((SOGoUser)context.getPrincipal().getUser()).getUserID();
		} catch (Exception e) {
			userid = context.getPrincipal().getUsername();
		}
		_context = context;
	
		// We try to load our JDBC driver and fetch our table names
		try {
			ResultSet rs;
			Statement s;

			_connection = SOGoUtilities.initDatabaseDriver(this, _log);
			
			_log.info("Context's user ID: " + userid);
			_log.info("Context sync mode: " + _context.getSyncMode());
			_log.info("Context conflict resolution: " + _context.getConflictResolution());
			_log.info("Context query: " + _context.getSourceQuery());
			
			// We get out tags and their associated location. Note that _sync_tags might
			// get modified when we call getSyncTagsLocation() in order to drop tags
			// that point to unknown locations.
			_sync_tags = SOGoUtilities.getSyncTags(this, _source_type, context, userid, _log);
			_sync_tags_location = SOGoUtilities.getSyncTagsLocation(this, context, _sync_tags, _log);
			
			// We fetch the table names used for the synchronization. 
			// Start with the Contact table / quick table
 			s = _connection.createStatement();
 			rs = s.executeQuery("SELECT c_location, c_quick_location FROM sogo_folder_info WHERE c_path2 = '" + userid + "' AND c_path3 = 'Contacts' and c_path4 = 'personal'");
		
 			if (rs.next()) {
 				_contact_table = rs.getString(1).substring(rs.getString(1).lastIndexOf('/')+1);
 				_contact_quick_table = rs.getString(2).substring(rs.getString(2).lastIndexOf('/')+1);
 			}
 			
 			rs.close();
 			
 			// Then, initialize the Calendar table / quick table
 			rs = s.executeQuery("SELECT c_location FROM sogo_folder_info WHERE c_path2 = '" + userid + "' AND c_path3 = 'Calendar' and c_path4 = 'personal'");
 			
 			if (rs.next()) {
 				_calendar_table = rs.getString(1).substring(rs.getString(1).lastIndexOf('/')+1);
 			}
 			
 			rs.close();
 			s.close();
 					
		} catch (SQLException e) {
			_log.info("Couldn't connect to the database: " + e.toString());
		}
	
		_charset = context.getPrincipal().getDevice().getCharset();
		_log.info("Device's charset: " + _charset);
		_log.info("Device's timezone: " + context.getPrincipal().getDevice().getTimeZone());
	}
	
	/**
	 * 
	 */
    public void commitSync() throws SyncSourceException {
    	_log.info("In commitSync()...");
    	super.commitSync();
    }
	
	/**
	 * 
	 */
	public void endSync() throws SyncSourceException {
		super.endSync();
		
		_log.info("In endSync()...");
		
		if (_connection != null) {
			try {
				_connection.close();
				_log.info("Closed the database connection.");
			} catch (SQLException e) {
				_log.info("Couldn't close the database connection: " + e.toString());
			}	
		} else {
			_log.info("Database connection never opened - skipping the close operation");
		}
	}

	/**
	 * This method is used to retrieve all sync items from the SOGo database.
	 */
	public SyncItemKey[] getAllSyncItemKeys() throws SyncSourceException {
		Vector<String> collections;
		Vector<SyncItemKey> v;
		ResultSet rs = null;
		Statement s = null;
		
		_log.info("getAllSyncItemKeys()");		

		collections = this.getSyncCollections();
		v = new Vector<SyncItemKey>();
		
		try {
			SyncItemKey key;
			int i;
			
			s = _connection.createStatement();
			
			for (i = 0; i < collections.size(); i++) {
				switch (_source_type) {
				case SOGO_CAL:
					rs = s.executeQuery("SELECT c_name FROM " + collections.get(i) + "_quick" + " WHERE c_component = 'vevent' OR c_component = 'vtodo'");	
					break;
				case SOGO_EVENT:
					rs = s.executeQuery("SELECT c_name FROM " + collections.get(i) + "_quick" + " WHERE c_component = 'vevent'");	
					break;
				case SOGO_TODO:
					rs = s.executeQuery("SELECT c_name FROM " + collections.get(i) + "_quick" + " WHERE c_component = 'vtodo'");
					break;
				default:
					rs = s.executeQuery("SELECT c_name FROM " + collections.get(i) + "_quick" + " WHERE c_component = 'vcard'");
				}
						
				while (rs.next()) {		
					key = new SyncItemKey(SOGoKey.decodeString(rs.getString(1)));
					_log.info("getAllSyncItemKeys(): " + key + "(" + collections.get(i) + ")");
					v.add(key);
				}
				
				rs.close();				
			}

			s.close();
			
		} catch (Exception e) {
			_log.error("Exception during getAllSyncItemKeys(): " + e.toString(), e);
		}
		
		return v.toArray(new SyncItemKey[0]);
	}

	/*
	 * @see SyncSource
	 */
	public SyncItemKey[] getNewSyncItemKeys(Timestamp since, Timestamp until)
			throws SyncSourceException {
		
		Vector<String> collections;
		PreparedStatement s = null;
		ResultSet rs = null;
		Vector<SyncItemKey> v;
		long start, end;
		
		start = (long)(since.getTime()/1000);
		end = (long)(until.getTime()/1000);
		
		collections = this.getSyncCollections();
		v = new Vector<SyncItemKey>();
		
		_log.info("getNewItemKeys(" + since + "(" + start + "), " + until + "(" + end + "))");
		
		try {
			SyncItemKey key;
			int i;
			
			for (i = 0; i < collections.size(); i++) {
				switch (_source_type) {
				case SOGO_CAL:
					s = _connection.prepareStatement("SELECT " + collections.get(i) + ".c_name FROM " + collections.get(i)  + ", " + collections.get(i) + "_quick" + " WHERE " + collections.get(i)  + ".c_creationdate >= ? AND " + collections.get(i)  + ".c_creationdate <= ? AND " + collections.get(i)  + ".c_name = " + collections.get(i) + "_quick" + ".c_name AND ( " + collections.get(i) + "_quick" + ".c_component = 'vevent' OR " + collections.get(i) + "_quick" + ".c_component = 'vtodo' ) AND " + collections.get(i)  + ".c_creationdate = " + collections.get(i)  + ".c_lastmodified ORDER BY " + collections.get(i)  + ".c_creationdate");
					break;
				case SOGO_EVENT:
					s = _connection.prepareStatement("SELECT " + collections.get(i) + ".c_name FROM " + collections.get(i)  + ", " + collections.get(i) + "_quick" + " WHERE " + collections.get(i)  + ".c_creationdate >= ? AND " + collections.get(i)  + ".c_creationdate <= ? AND " + collections.get(i)  + ".c_name = " + collections.get(i) + "_quick" + ".c_name AND " + collections.get(i) + "_quick" + ".c_component = 'vevent' AND " + collections.get(i)  + ".c_creationdate = " + collections.get(i)  + ".c_lastmodified ORDER BY " + collections.get(i)  + ".c_creationdate");
					break;
				case SOGO_TODO:
					s = _connection.prepareStatement("SELECT " + collections.get(i)  + ".c_name FROM " + collections.get(i)  + ", " + collections.get(i) + "_quick" + " WHERE " + collections.get(i)  + ".c_creationdate >= ? AND " + collections.get(i)  + ".c_creationdate <= ? AND " + collections.get(i)  + ".c_name = " + collections.get(i) + "_quick" + ".c_name AND " + collections.get(i) + "_quick" + ".c_component = 'vtodo' AND " + collections.get(i)  + ".c_creationdate = " + collections.get(i)  + ".c_lastmodified ORDER BY " + collections.get(i)  + ".c_creationdate");
					break;
				default:
					s = _connection.prepareStatement("SELECT " + collections.get(i)  + ".c_name FROM " + collections.get(i)  + ", " + collections.get(i) + "_quick" + " WHERE " + collections.get(i)  + ".c_creationdate >= ? AND " + collections.get(i)  + ".c_creationdate <= ? AND " + collections.get(i)  + ".c_name = " + collections.get(i) + "_quick" + ".c_name AND " + collections.get(i) + "_quick" + ".c_component = 'vcard' AND " + collections.get(i)  + ".c_creationdate = " + collections.get(i)  + ".c_lastmodified ORDER BY " + collections.get(i)  + ".c_creationdate");
				}
				
				s.setLong(1, start);
				s.setLong(2, end);
				rs = s.executeQuery();
				
				while (rs.next()) {
					key = new SyncItemKey(SOGoKey.decodeString(rs.getString(1)));
					_log.info("getNewSyncItemKeys(): " + key + "(" + collections.get(i) + ")");
					v.add(key);
				}
				
				rs.close();
				s.close();
			}

		} catch (Exception e) {
			_log.error("Exception during getNewSyncItemsKeys(): " + e.toString(), e);
		}
		
		return v.toArray(new SyncItemKey[0]);	
	}

	/*
	 * This method is used to retrieve items that were deleted on the server since
	 * the last synchronization occurred. SOGo keeps track of this using its quick
	 * tables - more precisely, the c_deleted column in the content tables.
	 * 
	 * We *might* return more data than we should here. For example, we might return
	 * deleted tasks when we should really return only deleted events but we don't
	 * care about this right now - every intelligent SyncML clients out there should
	 * deal with this as an event could have been created then deleted w/o prior
	 * synchronization.
	 * 
	 * @see SyncSource
	 */
	public SyncItemKey[] getDeletedSyncItemKeys(Timestamp since, Timestamp until)
			throws SyncSourceException {
		
		Vector<String> collections;
		PreparedStatement s = null;
		ResultSet rs = null;
		Vector<SyncItemKey> v;
		long start, end;
		
		start = (long)(since.getTime()/1000);
		end = (long)(until.getTime()/1000);
		
		collections = this.getSyncCollections();
		v = new Vector<SyncItemKey>();
		
		_log.info("getDeletedItemKeys(" + since + "(" + start + "), " + until + "(" + end + "))");
		
		
		try {
			SyncItemKey key;
			int i;
			
			for (i = 0; i < collections.size(); i++) {
				switch (_source_type) {
				case SOGO_CAL:
				case SOGO_EVENT:
				case SOGO_TODO:
					s = _connection.prepareStatement("SELECT c_name FROM " + collections.get(i) + " WHERE c_deleted = 1 AND c_lastmodified >= ? AND c_lastmodified <= ? ORDER BY c_lastmodified");
					break;
				default:
					s = _connection.prepareStatement("SELECT c_name FROM " + collections.get(i) + " WHERE c_deleted = 1 AND c_lastmodified >= ? AND c_lastmodified <= ? ORDER BY c_lastmodified");		
				}
				
				s.setLong(1, start);
				s.setLong(2, end);
				rs = s.executeQuery();
				
				while (rs.next()) {
					key = new SyncItemKey(SOGoKey.decodeString(rs.getString(1)));
					_log.info("getDeletedSyncItemKeys(): " + key + "(" + collections.get(i) + ")");
					v.add(key);
				}
				
				rs.close();
				s.close();
			}
		} catch (Exception e) {
			_log.error("Exception during getDeletedSyncItemsKeys(): " + e.toString(), e);
		}
		
		return v.toArray(new SyncItemKey[0]);	
	}

	/*
	 * @see SyncSource
	 */
	public SyncItemKey[] getUpdatedSyncItemKeys(Timestamp since, Timestamp until)
			throws SyncSourceException {

		Vector<String> collections;
		PreparedStatement s = null;
		ResultSet rs = null;
		Vector<SyncItemKey> v;
		long start, end;
		
		start = (long)(since.getTime()/1000);
		end = (long)(until.getTime()/1000);
		
		collections = this.getSyncCollections();
		v = new Vector<SyncItemKey>();
	
		_log.info("getUpdatedSyncItemKeys(" + since + "(" + start + "), " + until + "(" + end + "))");
		
		try {
			SyncItemKey key;
			int i;
			//
			// We check if c_creationdate is different from c_lastmodified since this value
			// is equal when new entries are created. We don't want to return the keys of
			// new elements in this method call.
			//
			for (i = 0; i < collections.size(); i++) {
				switch (_source_type) {
				case SOGO_CAL:
					s = _connection.prepareStatement("SELECT " + collections.get(i) + ".c_name FROM " + collections.get(i) + ", " + collections.get(i) + "_quick" + " WHERE " + collections.get(i) + ".c_lastmodified > ? AND " + collections.get(i) + ".c_lastmodified <= ? AND " + collections.get(i) + ".c_name = " + collections.get(i) + "_quick" + ".c_name AND ( " + collections.get(i) + "_quick" + ".c_component = 'vevent' OR " + collections.get(i) + "_quick" + ".c_component = 'vtodo' ) AND " + collections.get(i) + ".c_lastmodified != " + collections.get(i) + ".c_creationdate ORDER BY " + collections.get(i) + ".c_lastmodified");
					break;				
				case SOGO_EVENT:
					s = _connection.prepareStatement("SELECT " + collections.get(i) + ".c_name FROM " + collections.get(i) + ", " + collections.get(i) + "_quick" + " WHERE " + collections.get(i) + ".c_lastmodified > ? AND " + collections.get(i) + ".c_lastmodified <= ? AND " + collections.get(i) + ".c_name = " + collections.get(i) + "_quick" + ".c_name AND " + collections.get(i) + "_quick" + ".c_component = 'vevent' AND " + collections.get(i) + ".c_lastmodified != " + collections.get(i) + ".c_creationdate ORDER BY " + collections.get(i) + ".c_lastmodified");
					break;
				case SOGO_TODO:
					s = _connection.prepareStatement("SELECT " + collections.get(i) + ".c_name FROM " + collections.get(i) + ", " + collections.get(i) + "_quick" + " WHERE " + collections.get(i) + ".c_lastmodified > ? AND " + collections.get(i) + ".c_lastmodified <= ? AND " + collections.get(i) + ".c_name = " + collections.get(i) + "_quick" + ".c_name AND " + collections.get(i) + "_quick" + ".c_component = 'vtodo' AND " + collections.get(i) + ".c_lastmodified != " + collections.get(i) + ".c_creationdate ORDER BY " + collections.get(i) + ".c_lastmodified");
					break;
				default:
					s = _connection.prepareStatement("SELECT " + collections.get(i) + ".c_name FROM " + collections.get(i) + ", " + collections.get(i) + "_quick" + " WHERE " + collections.get(i) + ".c_lastmodified > ? AND " + collections.get(i) + ".c_lastmodified <= ? AND " + collections.get(i) + ".c_name = " + collections.get(i) + "_quick" + ".c_name AND " + collections.get(i) + "_quick" + ".c_component = 'vcard' AND " + collections.get(i) + ".c_lastmodified != " + collections.get(i) + ".c_creationdate ORDER BY " + collections.get(i) + ".c_lastmodified");
				}
				
				s.setLong(1, start);
				s.setLong(2, end);
				
				rs = s.executeQuery();
				
				while (rs.next()) {
					key = new SyncItemKey(SOGoKey.decodeString(rs.getString(1)));
					_log.info("getUpdatedSyncItemKeys(): " + key + "(" + collections.get(i) + ")");
					v.add(key);
				}
				
				rs.close();
				s.close();	
			}


		} catch (Exception e) {
			_log.error("Exception during getDeletedSyncItemsKeys(): " + e.toString(), e);
		}
		
		return v.toArray(new SyncItemKey[0]);	
	}

	/*
	 * @see SyncSource
	 */
	@SuppressWarnings(value={"unchecked"})
	public SyncItem getSyncItemFromId(SyncItemKey syncItemKey)
			throws SyncSourceException {

		PreparedStatement s = null;
		ResultSet rs = null;
		String key, tag, table_name;
		Vector<String> tags;
		
		boolean b, must_secure, must_encode;
		int i;
		
		_log.info("getSyncItemFromId(" + syncItemKey + ")");
		
		key = SOGoKey.encodeString((String)syncItemKey.getKeyValue());
		
		// We first look in the personal calendar, if we've found the item, we
		// stop here and process it. Otherwise, we'll look at tagged calendars
		// and use one of them if the item is found
		tags = new Vector(_sync_tags_location.keySet());
		tags.insertElementAt(" ", 0);
		must_secure = false;
		must_encode = false;
		
		try {
			// We loop forward, in order to get the personal folder first.
			for (i = 0; i < tags.size(); i++) {
				
				tag = (i == 0 ? null : tags.get(i));
				b = false;
				
				switch (_source_type) {
				case SOGO_CAL:
					// FIXME: Symbian combined event/task sync 
				case SOGO_EVENT:
				case SOGO_TODO:
					// We check if the entry exists in the quick table
					table_name = (i == 0 ? _calendar_table : (String)_sync_tags_location.get(tag));
					s = _connection.prepareStatement("SELECT c_name FROM " + table_name + "_quick" + " WHERE c_name = ?");
					s.setString(1, key);
					rs = s.executeQuery();
					b = rs.next();
					rs.close();
					s.close();
					
					s = _connection.prepareStatement("SELECT c_content FROM " + table_name + " WHERE c_name = ?");
					break;
				default:
					// We check if the entry exists in the quick table
					table_name = (i == 0 ? _contact_table : (String)_sync_tags_location.get(tag));
					s = _connection.prepareStatement("SELECT c_name FROM " + table_name + "_quick" + " WHERE c_name = ?");
					s.setString(1, key);
					rs = s.executeQuery();
					b = rs.next();
					rs.close();
					s.close();
					
					s = _connection.prepareStatement("SELECT c_content FROM " + table_name + " WHERE c_name = ?");
				}
				
				s.setString(1, key);
				rs = s.executeQuery();
			
				if (rs.next()) {
					SyncItem item;
		
					int type, classification;
					byte content[];
					
					// If our entry does exist in the content table but not in the quick table, we delete
					// it from the content table and return null so the client can send back the data
					if (!b) {
						PreparedStatement s2;
						
						s2 = _connection.prepareStatement("DELETE FROM " + table_name + " WHERE c_name = ?");
						s2.setString(1, key);
						s2.executeUpdate();
						
						// We cleanup and return
						s2.close();
						rs.close();
						s.close();
						_connection.commit();
						return null;
					}
				
					type = SOGoUtilities.getPreferredItemType(_context, _source_type, SOGoUtilities.RX);
					_log.info("Preferred item type: " + type);
				
					// We create our new item. The item type *must* be specified - otherwise,
					// there will be an exception generated in Funambol.
					item = new SyncItemImpl(this, (String)syncItemKey.getKeyValue(), SyncItemState.UNKNOWN); 
					
					// We check if we must secure the component
					classification = SOGoACLManager.SOGoACLPublic;
					if (tag != null && this.getCollectionForTag(tag) != null) {
						int roles, role;
					
						classification = SOGoACLManager.getRoleFromClassification(item, this, _context, _log, table_name); 
						roles = SOGoACLManager.getACL(this, _context, _log, table_name, (String)_sync_tags.get(tag), classification);
						role = classification | SOGoACLManager.SOGoACLDAndTViewer;
						
						_log.info("classification: " + classification + " role: " + role + " roles: " + roles);
						
						// No access, we return null. The item will then be removed from the items list to send. We make sure
						// to not only check for SOGoACLNone as the user might have the ObjectCreator or ObjectModifier right. 
						if (roles < SOGoACLManager.SOGoACLViewer) {
							rs.close();
							s.close();
							return null;
						}
							
						if (roles != SOGoACLManager.SOGoACLOwner && SOGoACLManager.hasACL(roles, role))
							must_secure = true;
					}
					
					_log.info("must secure? " + must_secure);
					
					if (_source_type == SOGO_EVENT || _source_type == SOGO_CAL ) {
						if (type == X_S4J_SIFE) {
							// No need to convert to v1 here as we use the ICalendarParser class.
							item.setType("text/x-s4j-sife");
							content = SOGoUtilities.vEventToSIFE(rs.getString(1).getBytes(), tag, (String)syncItemKey.getKeyValue(), must_secure, classification, this, _context, _log);
							must_encode = true;
						} else if (type == VCALENDAR_VERSION_20) {
							// FIXME: must secure content
							item.setType("text/x-vcalendar");
							content = rs.getString(1).getBytes();
						} else {
							item.setType("text/x-vcalendar");
							content = SOGoUtilities.vCalendarV2toV1(rs.getString(1).getBytes(), tag, (String)syncItemKey.getKeyValue(), must_secure, classification, this, _context, _log);
						}
					} 
					else if (_source_type == SOGO_TODO || _source_type == SOGO_CAL ) {
						if (type == X_S4J_SIFT) {
							// No need to convert to v1 here as we use the ICalendarParser class.
							item.setType("text/x-s4j-sift");
							content = SOGoUtilities.vTodoToSIFT(rs.getString(1).getBytes(), tag, (String)syncItemKey.getKeyValue(), must_secure, classification, this, _context, _log);
							must_encode = true;
						} else if (type == VCALENDAR_VERSION_20) {
							// FIXME: must secure content
							item.setType("text/x-vcalendar");
							content = rs.getString(1).getBytes();
						} else {
							item.setType("text/x-vcalendar");
							content = SOGoUtilities.vCalendarV2toV1(rs.getString(1).getBytes(), tag, (String)syncItemKey.getKeyValue(), must_secure, classification, this, _context, _log);
						}
					} else {
						if (type == X_S4J_SIFC) {
							// FIXME: call v3tov21 first
							item.setType("text/x-s4j-sifc");
							content = SOGoUtilities.vCardToSIFC(rs.getString(1).getBytes(), this, _log);
							must_encode = true;
						} else if (type == VCARD_VERSION_30) {
							item.setType("text/x-vcard");
							content = rs.getString(1).getBytes();
						} else {
							item.setType("text/x-vcard");
							content = SOGoUtilities.vCardV3toV21(rs.getString(1).getBytes(), this, _context, _log);
						}
					}
					
					// We always encode using b64 the SIF-* data.
					if (must_encode) {
						item.setFormat(Constants.FORMAT_B64);
						item.setContent(Base64.encode(content));
					} else {
						item.setContent(content);
					}
					
					rs.close();
					s.close();
					return item;
				} // if (rs.next()) ...
				
				rs.close();
				s.close();
			}
		} catch (Exception e) {
			_log.error("Exception during getSyncItemFromId(): " + e.toString(), e);
		}
		
		// Item not found...
		return null;
	}
	
	/**
	 * 
	 * @param syncItemKey
	 * @param time The time of the deletion
	 * @param softdelete If true, we do NOT delete the item from the server.
	 * @throws SyncSourceException
	 */
	@SuppressWarnings(value={"unchecked"})
	public void removeSyncItem(SyncItemKey syncItemKey, Timestamp time, boolean softdelete) throws SyncSourceException {

		String tag, table_name;
		Vector<String> tags;
		PreparedStatement s;
		String key;
		int i;
		
		_log.info("removeSyncItem(" + syncItemKey + " , " + time + " , soft? " + softdelete + ")");

		if (softdelete) return;
		
		// We find in which calendar the event is
		tags = new Vector(_sync_tags_location.keySet());
		tags.insertElementAt(" ", 0);
		table_name = null;
		tag = null;
		
		_log.info("Tags: " + tags.toString() + " count: " + tags.size());
		
		for (i = 0; i < tags.size(); i++) {
			tag = tags.get(i);			
			table_name = getTableName(i, _source_type, tag);
			
			if (this.getSyncItemKeyInCollection(syncItemKey, table_name) != null)
				break;
		}
		
		_log.info("Target tag: " + tag + " location: " + table_name + " i:" + i);
		
		if (table_name == null || i == 0 || i == tags.size()) {
			table_name = getTableName(0, _source_type, tag);
			tag = null;
		}
		
		try {			
			key = SOGoKey.encodeString((String)syncItemKey.getKeyValue());
			
			// We check if we can remove items from the collection (ObjectEraser)
			if (tag != null && this.getCollectionForTag(tag) != null) {
				int roles;

				roles = SOGoACLManager.getACL(this, _context, _log, table_name, (String)_sync_tags.get(tag), 0);
				
				if (roles != SOGoACLManager.SOGoACLOwner && !SOGoACLManager.hasACL(roles, SOGoACLManager.SOGoACLObjectEraser))
					throw new SyncSourceException("No access rights on " + table_name + " - ObjectEraser is needed");
			}
			
			switch (_source_type) {
			case SOGO_CAL:
			case SOGO_EVENT:
			case SOGO_TODO:
				// First, we update the entry from our real table
				s = _connection.prepareStatement("UPDATE " + table_name + " SET c_deleted = 1, c_lastmodified = ? WHERE c_name = ?");
				s.setLong(1, (long)(new java.util.Date()).getTime()/1000);
				s.setString(2, key);
				s.executeUpdate();
				s.close();
				
				// Then, we delete the entry from our quick table
				s = _connection.prepareStatement("DELETE FROM " + table_name + "_quick" + " WHERE c_name = ?");
				s.setString(1, key);
				s.executeUpdate();
				s.close();
				break;
				
			default:
				// First, we update the entry from our real table
				s = _connection.prepareStatement("UPDATE " + table_name + " SET c_deleted = 1, c_lastmodified = ? WHERE c_name = ?");
				s.setLong(1, (long)(new java.util.Date()).getTime()/1000);
				s.setString(2, key);
				s.executeUpdate();
				s.close();
				
				// Then, we delete the entry from our quick table
				s = _connection.prepareStatement("DELETE FROM " + table_name + "_quick" + " WHERE c_name = ?");
				s.setString(1, key);
				s.executeUpdate();
				s.close();
			}
			
			_connection.commit();
			
		} catch (Exception e) {
			if (e instanceof SyncSourceException)
				throw (SyncSourceException)e;
				
			_log.info("We got an exception while creating a statement: that probably means we're no longer connected.");
		}
	}
	
	private String getSyncItemKeyInCollection(SyncItemKey syncItemKey, String collection) {
		String key;

		key = null;
		
		try {
			PreparedStatement s;
			ResultSet rs;

			key = SOGoKey.encodeString((String)syncItemKey.getKeyValue());

			s = _connection.prepareStatement("SELECT c_name FROM " + collection + "_quick" + " WHERE c_name = ?");
			s.setString(1, key);
			rs = s.executeQuery();
			
			if (rs.next()) {
				key = rs.getString(1);
			} else {
				key = null;
			}
			
			rs.close();
			s.close();
			
		} catch (Exception e) {
			_log.info("We got an exception while creating a statement: that probably means we're no longer connected.");
		}
			
		return key;
	}

	private String getTableName(int index, int type, String tag) {
		String s;
		
		if (type == SOGO_CONTACT)
			s = (index == 0 ? _contact_table : (String)_sync_tags_location.get(tag));
		else
			s = (index == 0 ? _calendar_table : (String)_sync_tags_location.get(tag));
		
		return s;
	}
	/*
	 * @see SyncSource
	 */
	@SuppressWarnings(value={"unchecked"})
	public SyncItem updateSyncItem(SyncItem syncItem)
			throws SyncSourceException {

		String tag, table_name;
		Vector<String> tags;
		int type, i;
		
		_log.info("updateSyncItem(" + syncItem.getKey().getKeyAsString() + ")");
		_log.info("updateSyncItem - type: " + syncItem.getType());
		
		if (_context.getSyncMode() == AlertCode.SLOW) {
			_log.info("Skipping updates from the client after a slow-sync.");
			return syncItem;
		}
		
		type = SOGoUtilities.getPreferredItemType(_context, _source_type, SOGoUtilities.RX);
		_log.info("Preferred item type: " + type);

		// We find in which calendar the event is
		tags = new Vector(_sync_tags_location.keySet());
		tags.insertElementAt(" ", 0);
		table_name = null;
		tag = null;
		
		_log.info("Tags: " + tags.toString() + " count: " + tags.size());
		
		for (i = 0; i < tags.size(); i++) {
			tag = tags.get(i);			
			table_name = getTableName(i, _source_type, tag);
			
			if (this.getSyncItemKeyInCollection(syncItem.getKey(), table_name) != null)
				break;
		}
		
		_log.info("Target tag: " + tag + " location: " + table_name + " i:" + i);
		
		if (table_name == null || i == 0 || i == tags.size()) {
			table_name = getTableName(0, _source_type, tag);
			tag = null;
		}
		
		_log.info("Target tag: " + tag + " location: " + table_name);

		if (_context.getConflictResolution() == SyncContext.CONFLICT_RESOLUTION_CLIENT_WINS) {
			switch (_source_type) {
			case SOGO_CAL:
				String content;
				int vcal_type;

				content = new String(syncItem.getContent());
				vcal_type = 0;
				
				if (type == X_S4J_SIFE || type == X_S4J_SIFT) {
					syncItem.setContent(SOGoUtilities.SIFTovCalendar(syncItem.getContent(), this, _log));
				} else {
					try{	
						// decide whether a SOGO_CAL entry (vcal) is an vevent or vtodo:
						_log.info("vcal: item content:".concat(content));
						if(content.contains("BEGIN:VTODO")){
							vcal_type = SOGO_TODO;
							_log.info("vcal: item is vtodo");
						} else if(content.contains("BEGIN:VEVENT")){
							vcal_type = SOGO_EVENT;
							_log.info("vcal: item is vevent");
						} else {
							_log.warn("vcal: item type not detected!");
						}
					} catch (Exception e){
						_log.error("Exception occured in addSyncItem() - " + e.getMessage(), e);
					}
				}
				if (vcal_type == SOGO_EVENT)	
					SOGoEventUtilities.updatevEventSyncItem(syncItem, tag, table_name, this, _context, _log);
				else if (vcal_type == SOGO_TODO)
					SOGoTaskUtilities.updatevTodoSyncItem(syncItem, tag, table_name, this, _context, _log);
				else
					_log.warn("item of type vcal doesnt belong to vevent or vtodo, not updated! \ncontent is:".concat(content));

				break;
							
			case SOGO_EVENT:
				_log.info("sogo-event: item content:".concat(new String(syncItem.getContent())));
				if (type == X_S4J_SIFE) {
					syncItem.setContent(SOGoUtilities.SIFTovCalendar(syncItem.getContent(), this, _log));
				}
				SOGoEventUtilities.updatevEventSyncItem(syncItem, tag, table_name, this, _context, _log);
			break;
				
			case SOGO_TODO:
				if (type == X_S4J_SIFT) {
					syncItem.setContent(SOGoUtilities.SIFTovCalendar(syncItem.getContent(), this, _log));
				}
				SOGoTaskUtilities.updatevTodoSyncItem(syncItem, tag, table_name, this, _context, _log);
				break;
				
			case SOGO_CONTACT:
			default:
				if (type == X_S4J_SIFC) {
					syncItem.setContent(SOGoUtilities.SIFCTovCard(syncItem.getContent(), this, _log));
				}
				SOGoContactUtilities.updatevCardSyncItem(syncItem, this, _context, _log);
			}
		}
		// We consider for now that the server wins if:
		//  - the conflict resolution is CONFLICT_RESOLUTION_SERVER_WINS
		//  - we must merge the data
		else {	
			syncItem = getSyncItemFromId(syncItem.getKey());
		}
		
		return syncItem;
	}
	
	/**
	 */
	public SyncItem addSyncItem(SyncItem syncItem) throws SyncSourceException {
		SyncItem itemOnServer;

		_log.info("addSyncItem(" + syncItem.getKey().getKeyAsString() + ")");
		_log.info("addSyncItem - type: " + syncItem.getType());
		itemOnServer = this.getSyncItemFromId(syncItem.getKey());	
				
		if (itemOnServer == null) {
			int type, vcal_type=0;
			String content = new String(syncItem.getContent());
			_log.info("addSyncItem - content: " + content);
			type = SOGoUtilities.getPreferredItemType(_context, _source_type, SOGoUtilities.RX);
			_log.info("Preferred item type: " + type);
			
			switch (_source_type) {
			case SOGO_CAL:
				if (type == X_S4J_SIFE || type == X_S4J_SIFT) {
					syncItem.setContent(SOGoUtilities.SIFTovCalendar(syncItem.getContent(), this, _log));
                }
                try{
                    // decide whether a SOGO_CAL entry (vcal) is an vevent or vtodo:
                    // _log.info("vcal: item content:".concat(content));
                    if (content.contains("BEGIN:VTODO")) {
                        vcal_type = SOGO_TODO;
                        _log.info("vcal: item is vtodo");
                    } else if(content.contains("BEGIN:VEVENT")) {
                        vcal_type = SOGO_EVENT;
                        _log.info("vcal: item is vevent");
                    } else {
                        _log.warn("vcal: item type not detected!");
                    }
                    if (vcal_type == SOGO_EVENT)
                        SOGoEventUtilities.addvEventSyncItem(syncItem, this, _context, _log);
                    else if (vcal_type == SOGO_TODO)
                        SOGoTaskUtilities.addvTodoSyncItem(syncItem, this, _context, _log);
                    else
                        _log.warn("item of type vcal doesnt belong to vevent or vtodo, not added!");
                } catch (Exception e){
                    _log.error("Exception occured in addSyncItem() - " + e.getMessage(), e);
                }

				break;
				
			case SOGO_EVENT:
				// _log.info("sogo-event: item content:".concat(content));
				if (type == X_S4J_SIFE) {
					syncItem.setContent(SOGoUtilities.SIFTovCalendar(syncItem.getContent(), this, _log));
				}
				SOGoEventUtilities.addvEventSyncItem(syncItem, this, _context, _log);
				break;
				
			case SOGO_TODO:
				// _log.info("sogo-todo: item content:".concat(content));
				if (type == X_S4J_SIFT) {
					syncItem.setContent(SOGoUtilities.SIFTovCalendar(syncItem.getContent(), this, _log));	
				}
				SOGoTaskUtilities.addvTodoSyncItem(syncItem, this, _context, _log);
				break;
				
			case SOGO_CONTACT:
			default:
				if (type == X_S4J_SIFC) {
					syncItem.setContent(SOGoUtilities.SIFCTovCard(syncItem.getContent(), this, _log));
				}
				SOGoContactUtilities.addvCardSyncItem(syncItem, this, _context, _log);
			}
		} else {
			_log.info("Item existed on server when trying to add it: " + itemOnServer.toString());
			_log.info("Item content: " + new String(itemOnServer.getContent()));
		}

		return syncItem;
	}

	/**
	 * 
	 */
	@SuppressWarnings(value={"unchecked"})
	public SyncItemKey[] getSyncItemKeysFromTwin(SyncItem syncItem)
			throws SyncSourceException {
		ArrayList<SyncItemKey> l;
		String c_type;
		ResultSet rs;
		Statement s;
		int vcal_type = 0;
		
		l = new ArrayList<SyncItemKey>();
		c_type = null;
		
		switch (_source_type) {

		// For events and tasks, we compare the following fields
		//  
		// DTSTART 	-> c_startdate
		// DTEND	-> c_enddate
		// SUMMARY	-> c_title
		//		
		case SOGO_CAL:
			c_type = "vcal";
		case SOGO_EVENT:
			c_type = "vevent";
		case SOGO_TODO:
			try {
				String c_title, content, tag, summary, location;
				CalendarContent cc = null;
				
				long c_startdate, c_enddate, start, end;
				int type, a,b ;
				
				
				type = SOGoUtilities.getPreferredItemType(_context, _source_type, SOGoUtilities.RX);
				
				if (c_type == null) {
					c_type = "vtodo";
				}
				
				if (type == SOGoSyncSource.VCALENDAR_VERSION_10 || type == SOGoSyncSource.VCALENDAR_VERSION_20) {
					content = SOGoSanitizer.sanitizevCalendarInput(syncItem.getContent(), syncItem.getKey().getKeyAsString(), _log);
				} else {
					content = null;
				}
				if (c_type == "vcal") {
					if (vcal_type == SOGO_EVENT)
						cc = SOGoUtilities.getCalendarContentFromSyncItem(content, "vevent", syncItem, this, type, _log);
					else if(vcal_type == SOGO_TODO) 
						cc = SOGoUtilities.getCalendarContentFromSyncItem(content, "vtodo", syncItem, this, type, _log);
				} else {
					cc = SOGoUtilities.getCalendarContentFromSyncItem(content, c_type, syncItem, this, type, _log);
				}
				
				if (_source_type == SOGO_EVENT || (_source_type == SOGO_CAL && vcal_type == SOGO_EVENT) ) {
					start = SOGoEventUtilities.getStartDate((Event)cc, this, _context, _log);
					end = SOGoEventUtilities.getEndDate((Event)cc, this, _context, _log);
				} else {
					start = SOGoTaskUtilities.getStartDate((Task)cc, this, _context, _log);
					end = SOGoTaskUtilities.getDueDate((Task)cc, this, _context, _log);
				}
				
				summary = cc.getSummary().getPropertyValueAsString().trim();

				// If the title is tagged, we untag it and we find the relevant target calendar
				a = summary.indexOf('[');
				b = summary.indexOf(']');
				location = null;
				tag = null;
				
				if (a == 0 && b > 0) {				
					tag = summary.substring(1, b).toLowerCase();
					summary = summary.substring(b+2);
									
					if (tag.length() > 0) {
						location = this.getLocationForTag(tag);
						_log.info("found target location for item: " + location);
					} else {
						tag = null;
					}
				}
				
				if (tag == null || location == null)
					location = _calendar_table;
				
				s = _connection.createStatement();
				rs = s.executeQuery("SELECT c_startdate, c_enddate, c_title, c_name FROM " + location + "_quick" + " WHERE c_component = '" + c_type + "'");
				
				 while (rs.next()) {
						c_startdate = rs.getLong(1);
						c_enddate = rs.getLong(2);
						c_title = rs.getString(3);
						
						if (c_startdate == start && c_enddate == end && summary.equalsIgnoreCase(c_title)) {
							_log.info("Found calendar entry in database: " + c_title);
							l.add(new SyncItemKey(rs.getString(4)));
							break;
						}
				 }
				 
				 rs.close();
				 s.close();
				
			} catch (Exception e) {
				_log.error("Exception occured in getSyncItemKeysFromTwin() - " + e.getMessage(), e);

			}
			break;
			
		// For contacts, we compare the following fields
		//
		// FN		-> c_cn
		// EMAIL	-> c_mail
		//
		case SOGO_CONTACT:
		default:
			try {
				String cn, c_cn, c_mail, c_o, o;
				ArrayList<Email> emails;
				Contact c;
				boolean found;
				int i;
				
				c = SOGoUtilities.getContactFromSyncItem(syncItem, SOGoUtilities.getPreferredItemType(_context, _source_type, SOGoUtilities.RX), _log);
				found = false;
				cn = "";
				o = "";
				
				// We try to get the name of the contact
				if (c.getName() != null && c.getName().getDisplayName() != null && c.getName().getDisplayName().getPropertyValueAsString() != null) {
					cn = c.getName().getDisplayName().getPropertyValueAsString();
					_log.info("In getSyncItemKeysFromTwin: cn = |" + cn + "|");

				}
				else if (c.getName() != null) {
					String firstname, lastname;
					
					firstname = null;
					lastname = null;
					
					if (c.getName().getFirstName() != null)
						firstname = c.getName().getFirstName().getPropertyValueAsString();
					
					if (c.getName().getLastName() != null)
						lastname = c.getName().getLastName().getPropertyValueAsString();
													
					if (firstname != null)
						cn = firstname;
					
					if (lastname != null)
						if (firstname != null)
							cn = cn + " " + lastname;
						else 
							cn = lastname;

					_log.info("In getSyncItemKeysFromTwin: firtname = |" + firstname + "| lastname = |" + lastname + "| cn = |" + cn + "|");
				}
				
				
				// We get the organization
				if (c.getBusinessDetail().getCompany() != null)					
					o = c.getBusinessDetail().getCompany().getPropertyValueAsString();
					
				// We get the list of emails for the contact
				emails = new ArrayList<Email>();
				emails.addAll(c.getPersonalDetail().getEmails());
				emails.addAll(c.getBusinessDetail().getEmails());

				_log.info("In getSyncItemKeysFromTwin: cn = " + cn + " emails = " + emails.toString() + " o = " + o);
				
				s = _connection.createStatement();
				rs = s.executeQuery("SELECT c_cn, c_mail, c_o, c_name FROM " + _contact_quick_table);
										
				 while (rs.next()) {
					 
					c_cn = (rs.getString(1) == null ? "" : rs.getString(1));
					c_mail = (rs.getString(2) == null ? "" : rs.getString(2));
					c_o = (rs.getString(3) == null ? "" : rs.getString(3));
					_log.info("c_cn = " + c_cn + " c_mail = " + c_mail + " c_o = " + c_o);

					// We first compare the email, then, the name and finally the organization
					for (i = 0; i < emails.size(); i++) {
						if (c_mail.trim().length() > 0 && c_mail.trim().equalsIgnoreCase(emails.get(i).getPropertyValueAsString().trim())) {
							_log.info("c_mail matches for twin items (" + emails.get(i).getPropertyValueAsString().trim() + ")");
							found = true;
							break;
						}
					}
					
					if (!found) {
						if (c_cn.trim().length() > 0 && c_cn.trim().equalsIgnoreCase(cn.trim())) {
							_log.info("c_cn matches for twin items (" + cn + ")");
							found = true;
						}
					}
					
					if (!found) {
						if (c_o.trim().length() > 0 && c_o.trim().equalsIgnoreCase(o.trim())) {
							_log.info("c_o matches for twin items (" + o + ")");
							found = true;
						}
					}
					
					if (found)
						l.add(new SyncItemKey(rs.getString(4)));
				 }
				
				 rs.close();
				 s.close();
				 
			} catch (Exception e) {
				_log.error("Exception occured in getSyncItemKeysFromTwin() - " + e.getMessage(), e);				
			}
			break;
			
		}
				
		return (SyncItemKey[])l.toArray(new SyncItemKey[l.size()]);
	}

	/**
	 */
	public void setOperationStatus(String operation, int statusCode,
			SyncItemKey[] keys) {

		StringBuffer message = new StringBuffer("Received status code '");
		message.append(statusCode).append("' for a '").append(operation)
				.append("'").append(" for this items (").append(keys.length).append("): ");

		for (int i = 0; i < keys.length; i++) {
			message.append("\n- " + keys[i].getKeyAsString());
		}
		
		_log.info(message.toString());
	}
	
	/**
	 * 
	 * @return
	 */
	public String getDatabaseURL() {
		return _url;
	}
	
	/**
	 * 
	 * @param url
	 */
	public void setDatabaseURL(String url) {
		_url = url;
	}
	
	/**
	 * 
	 * @return
	 */
	public String getDatabaseUsername() {
		return _username;
	}
	
	/**
	 * 
	 * @param username
	 */
	public void setDatabaseUsername(String username) {
		_username = username;
	}
	
	/**
	 * 
	 * @return
	 */
	public String getDatabasePassword() {
		return _password;
	}
	
	/**
	 * 
	 * @param password
	 */
	public void setDatabasePassword(String password) {
		_password = password;
	}
	
	/**
	 * 
	 * @return
	 */
	public Connection getDBConnection() {
		return _connection;
	}
	
	/**
	 * 
	 * @return
	 */
	public String getCalendarTable() {
		return _calendar_table;
	}
	
	/**
	 * 
	 * @return
	 */
	public String getContactTable() {
		return _contact_table;
	}
	
	/**
	 * 
	 * @return
	 */
	public String getContactQuickTable() {
		return _contact_quick_table;
	}
	
	/**
	 * 
	 * @return
	 */
	public String getDeviceCharset() {
		return _charset;
	}
	
	@SuppressWarnings(value={"unchecked"})
	public Vector<String> getSyncCollections() {
		Vector<String> collections;
		
		collections = new Vector<String>();
		collections.addAll(_sync_tags_location.values());
		
		if (_source_type == SOGO_CONTACT)
			collections.insertElementAt(_contact_table, 0);
		else 
			collections.insertElementAt(_calendar_table, 0);

		return collections;
	}
	
	public String getCollectionForTag(String tag) {
		return (String)_sync_tags.get(tag);
	}
	
	public String getLocationForTag(String tag) {
		return (String)_sync_tags_location.get(tag);
	}
}
