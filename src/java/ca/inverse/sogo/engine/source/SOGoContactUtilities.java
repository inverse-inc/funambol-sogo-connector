/*
 * Copyright (C) 2007-2010 Inverse inc.
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
import java.util.TimeZone;
import com.funambol.common.pim.contact.Contact;
import com.funambol.common.pim.converter.ContactToVcard;
import com.funambol.common.pim.vcard.VcardParser;
import com.funambol.framework.engine.SyncItem;
import com.funambol.framework.engine.SyncItemState;
import com.funambol.framework.engine.source.SyncContext;
import com.funambol.framework.logging.FunambolLogger;


public class SOGoContactUtilities {

	/**
	 * This method is used to add a new vCard object in the SOGo database.
	 *
	 * @param item
	 * @param connection
	 * @param table
	 * @param quick_table
	 * @param log
	 * @return
	 */
	public static SyncItem addvCardSyncItem(SyncItem item, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		try {
			String c_name, c_givenname, c_cn, c_sn, c_o, c_ou, c_telephonenumber, c_mail, c_l, c_content;
			PreparedStatement s;
			Contact c;
		
			long timestamp;
			
			c = SOGoUtilities.getContactFromSyncItem(item, SOGoSyncSource.VCARD_VERSION_21, log);
			c_givenname = c.getName().getFirstName().getPropertyValueAsString();
			c_cn = c.getName().getDisplayName().getPropertyValueAsString();
			c_sn = c.getName().getLastName().getPropertyValueAsString();
			c_o = c.getBusinessDetail().getCompany().getPropertyValueAsString();
			c_ou = c.getBusinessDetail().getDepartment().getPropertyValueAsString();
			c_telephonenumber = SOGoUtilities.getPreferredPhone(c);
			c_mail = SOGoUtilities.getPreferredEmail(c);
			c_l = SOGoUtilities.getPreferredCity(c);
			
			if (c_cn == null || c_cn.trim().length() == 0)
				c_cn = c_givenname + " " + c_sn;

			log.info("givenname: " + c_givenname);
			log.info("cn: " + c_cn);
			log.info("sn: " + c_sn);
			log.info("o: " + c_o);
			log.info("ou: " + c_ou);
			log.info("telephone_number: " + c_telephonenumber);
			log.info("mail: " + c_mail);
			log.info("locality: " + c_l);
			log.info("Parsed contact: " + c.toString()); 
			
			c_name = SOGoKey.encodeString(item.getKey().getKeyAsString());
			c_content = SOGoSanitizer.sanitizevCardOutput(item.getContent(), c, context, log);

			log.info("c_name: " + c_name);

            // Get creation and modification time from sync item
            timestamp = item.getTimestamp().getTime() / 1000;

			// We insert into our normal table
			s = source.getDBConnection().prepareStatement("INSERT INTO " + source.getContactTable() + " (c_name, c_content, c_creationdate, c_lastmodified, c_version) VALUES (?,?,?,?,?)");
			s.setString(1, c_name);
			s.setString(2, c_content);
			s.setLong(3, timestamp);
			s.setLong(4, timestamp);
			s.setInt(5, 0);
			s.executeUpdate();
			s.close();
			
			// We insert into our quick table
			// FIXME: decode screenname
			s = source.getDBConnection().prepareStatement("INSERT INTO " + source.getContactQuickTable() + " (c_name, c_givenname, c_cn, c_sn, c_o, c_ou, c_telephonenumber, c_mail, c_l, c_component) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
			s.setString(1, c_name);
			s.setString(2, c_givenname);
			s.setString(3, c_cn);
			s.setString(4, c_sn);
			s.setString(5, c_o);
			s.setString(6, c_ou);
			s.setString(7, c_telephonenumber);
			s.setString(8, c_mail);
			s.setString(9, c_l);
			s.setString(10, "vcard");
			s.executeUpdate();
			s.close();
			
			source.getDBConnection().commit();
			
			item.setState(SyncItemState.SYNCHRONIZED);
		} catch (Exception e) {
			log.error("Exception occured in addvCardSyncItem: " + e.toString(), e);
		}

		return item;
	}
	
	/**
	 * 
	 * @param item
	 * @param connection
	 * @param table
	 * @param quick_table
	 * @param log
	 * @return
	 */
	public static SyncItem updatevCardSyncItem(SyncItem item, SOGoSyncSource source, SyncContext context, FunambolLogger log) {
		try {
			String c_name, c_givenname, c_cn, c_sn, c_o, c_ou, c_telephonenumber, c_mail, c_l, c_content;
			PreparedStatement s;
			Contact c;
			
			boolean doMerge = true;
						
			c = SOGoUtilities.getContactFromSyncItem(item, SOGoSyncSource.VCARD_VERSION_21, log);	
			c_givenname = c.getName().getFirstName().getPropertyValueAsString();
			c_cn = c.getName().getDisplayName().getPropertyValueAsString();
			c_sn = c.getName().getLastName().getPropertyValueAsString();
			
			// Display name might be empty, if so, we merge c_givenname and c_sn
			// This might happen (at least, it does with Funambol 8.0.1) when we get
			// in the card:  N:Foo;Mister;;;;
			// getDisplayName() will be empty while getFirstName() ad getLastName() will
			// have the right values.
			if (c_cn == null || c_cn.trim().length() == 0)
				c_cn = c_givenname + " " + c_sn;
			
			c_o = c.getBusinessDetail().getCompany().getPropertyValueAsString();
			c_ou = c.getBusinessDetail().getDepartment().getPropertyValueAsString();
			c_telephonenumber = SOGoUtilities.getPreferredPhone(c);
			c_mail = SOGoUtilities.getPreferredEmail(c);
			c_l = SOGoUtilities.getPreferredCity(c);

			c_name = SOGoKey.encodeString(item.getKey().getKeyAsString());
			c_content = SOGoSanitizer.sanitizevCardOutput(item.getContent(), c, context, log);
			
			// We do merge contacts but we convert the one coming from the database
			// to v2.1 prior the merge otherwise Funambol can't parse it.
			if (doMerge) {
				Contact originalContact;
				String merged_content;
				ResultSet rs;

				s = source.getDBConnection().prepareStatement("SELECT c_content FROM " + source.getContactTable() + " WHERE c_name = ?");
				s.setString(1, c_name);
				rs = s.executeQuery();
				
				originalContact = null;
				merged_content = null;

				if (rs.next()) {
					ContactToVcard conv;
					VcardParser p;
					
					//log.info("Merging original contact: " + rs.getString(1) + " with: " + c_content);

					p = new VcardParser(new ByteArrayInputStream(SOGoUtilities.vCardV3toV21(rs.getString(1).getBytes(), source, context, log)), null, null);
					originalContact = p.vCard();
					
					c.merge(originalContact);					
					conv = new ContactToVcard(TimeZone.getTimeZone("GMT"), source.getDeviceCharset());
					merged_content = conv.convert(c);
				}

				if (originalContact != null) {
					c_content = merged_content;
				}
				
				// We clean our contact from empty properties once more
				c_content =  SOGoSanitizer.sanitizevCardOutput(c_content.getBytes(), c, context, log);
				
			} // if (doMerge) ...
			
			// We insert into our normal table
			s = source.getDBConnection().prepareStatement("UPDATE " + source.getContactTable() + " SET c_content = ?, c_lastmodified = ? WHERE c_name = ?");
			s.setString(1, c_content); 		                           // c_content
			s.setLong(2, item.getTimestamp().getTime() / 1000);        // c_lastmodified
			s.setString(3, c_name);									   // c_name
			s.executeUpdate();
			s.close();
						
			// We insert into our quick table
			// FIXME: decode screenname
			s = source.getDBConnection().prepareStatement("UPDATE " + source.getContactQuickTable() + " SET c_givenname = ?, c_cn = ?, c_sn = ?, c_o = ?, c_ou = ?, c_telephonenumber = ?, c_mail = ?, c_l = ? WHERE c_name = ?");
			s.setString(1, c_givenname);
			s.setString(2, c_cn);
			s.setString(3, c_sn);
			s.setString(4, c_o);
			s.setString(5, c_ou);
			s.setString(6, c_telephonenumber);
			s.setString(7, c_mail);
			s.setString(8, c_l);
			s.setString(9, c_name);
			s.executeUpdate();
			s.close();
			
			SOGoUtilities.updateContentVersion(source.getDBConnection(), source.getContactTable(), c_name);
			
			source.getDBConnection().commit();
			
			item.setState(SyncItemState.UPDATED);
		} catch (Exception e) {
			log.error("Exception occured in updatevCardSyncItem: " + e.toString(), e);
		}
		
		return item;
	}
}
