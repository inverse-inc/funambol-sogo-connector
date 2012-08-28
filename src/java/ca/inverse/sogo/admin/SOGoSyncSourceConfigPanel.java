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
package ca.inverse.sogo.admin;

import java.io.Serializable;

import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.TitledBorder;

import org.apache.commons.lang.StringUtils;

import ca.inverse.sogo.engine.source.SOGoSyncSource;

import com.funambol.framework.engine.source.ContentType;
import com.funambol.framework.engine.source.SyncSourceInfo;

import com.funambol.admin.AdminException;
import com.funambol.admin.ui.SourceManagementPanel;

/**
 * This class implements the configuration panel for the SOGoSyncSource
 */
public class SOGoSyncSourceConfigPanel extends SourceManagementPanel implements
		Serializable {

	//
	// Constants
	//
	public static final long serialVersionUID = 1;
	public static final String NAME_ALLOWED_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890-_.";

	//
	// Private data
	//
	private JLabel panelName = new JLabel();
	private TitledBorder titledBorder1;
	private JLabel nameLabel = new JLabel();
	private JTextField nameValue = new JTextField();
	private JLabel typeLabel = new JLabel();
	private JComboBox typeValue = new JComboBox(new String[] { "text/x-vcard",
			"text/x-vevent", "text/x-vtodo", "text/x-vcal" });
	private JLabel sourceURILabel = new JLabel();
	private JTextField sourceURIValue = new JTextField();
	private JLabel dbURLLabel = new JLabel();
	private JTextField dbURLValue = new JTextField();
	private JLabel dbUsernameLabel = new JLabel();
	private JTextField dbUsernameValue = new JTextField();
	private JLabel dbPasswordLabel = new JLabel();
	private JPasswordField dbPasswordValue = new JPasswordField();
	private JButton button = new JButton();

	private SOGoSyncSource syncSource = null;

	/**
	 * 
	 */
	public SOGoSyncSourceConfigPanel() {
		init();
	}

	/**
	 * 
	 */
	private void init() {
		this.setLayout(null);

		titledBorder1 = new TitledBorder("");

		panelName.setFont(titlePanelFont);
		panelName.setText("Edit SOGo SyncSource");
		panelName.setBounds(new Rectangle(14, 5, 316, 28));
		panelName.setAlignmentX(SwingConstants.CENTER);
		panelName.setBorder(titledBorder1);

		int y = 60;
		int dy = 30;
		sourceURILabel.setText("Source URI: ");
		sourceURILabel.setFont(defaultFont);
		sourceURILabel.setBounds(new Rectangle(14, y, 150, 18));
		sourceURIValue.setFont(defaultFont);
		sourceURIValue.setBounds(new Rectangle(170, y, 350, 18));

		y += dy;
		nameLabel.setText("Name: ");
		nameLabel.setFont(defaultFont);
		nameLabel.setBounds(new Rectangle(14, y, 150, 18));
		nameValue.setFont(defaultFont);
		nameValue.setBounds(new Rectangle(170, y, 350, 18));

		y += dy;
		typeLabel.setText("Supported type: ");
		typeLabel.setFont(defaultFont);
		typeLabel.setBounds(new Rectangle(14, y, 150, 18));
		typeValue.setFont(defaultFont);
		typeValue.setBounds(new Rectangle(170, y, 150, 18));

		y += dy;
		dbURLLabel.setText("Database URL: ");
		dbURLLabel.setFont(defaultFont);
		dbURLLabel.setBounds(new Rectangle(14, y, 150, 18));
		dbURLValue.setFont(defaultFont);
		dbURLValue.setBounds(new Rectangle(170, y, 350, 18));
		
		y += dy;
		dbUsernameLabel.setText("Database username:");
		dbUsernameLabel.setFont(defaultFont);
		dbUsernameLabel.setBounds(new Rectangle(14, y, 150, 18));
		dbUsernameValue.setFont(defaultFont);
		dbUsernameValue.setBounds(new Rectangle(170, y, 150, 18));

		y += dy;
		dbPasswordLabel.setText("Database password:");
		dbPasswordLabel.setFont(defaultFont);
		dbPasswordLabel.setBounds(new Rectangle(14, y, 150, 18));
		dbPasswordValue.setFont(defaultFont);
		dbPasswordValue.setBounds(new Rectangle(170, y, 150, 18));

		y += dy;
		button.setFont(defaultFont);
		button.setText("Add");
		button.setBounds(170, y, 70, 25);

		button.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent event) {
				try {
					validateValues();
					getValues();
					if (getState() == STATE_INSERT) {
						SOGoSyncSourceConfigPanel.this
								.actionPerformed(new ActionEvent(
										SOGoSyncSourceConfigPanel.this,
										ACTION_EVENT_INSERT, event
												.getActionCommand()));
					} else {
						SOGoSyncSourceConfigPanel.this
								.actionPerformed(new ActionEvent(
										SOGoSyncSourceConfigPanel.this,
										ACTION_EVENT_UPDATE, event
												.getActionCommand()));
					}
				} catch (Exception e) {
					notifyError(new AdminException(e.getMessage()));
				}
			}
		});

		this.add(panelName, null);
		this.add(nameLabel, null); this.add(nameValue, null);
		this.add(typeLabel, null); this.add(typeValue, null);
		this.add(sourceURILabel, null);	this.add(sourceURIValue, null);
		this.add(dbURLLabel); this.add(dbURLValue);
		this.add(dbUsernameLabel); this.add(dbUsernameValue);
		this.add(dbPasswordLabel); this.add(dbPasswordValue);
		this.add(button, null);
	}

	/**
	 * 
	 */
	public void updateForm() {
		if (!(getSyncSource() instanceof SOGoSyncSource)) {
			notifyError(new AdminException("This is not SOGoSyncSource! Unable to process SyncSource values."));
			return;
		}
		
		if (getState() == STATE_INSERT) {
			button.setText("Add");
		} else if (getState() == STATE_UPDATE) {
			button.setText("Save");
		}

		this.syncSource = (SOGoSyncSource)getSyncSource();

		sourceURIValue.setText(syncSource.getSourceURI());
		nameValue.setText(syncSource.getName());
		
		if (syncSource != null && syncSource.getInfo() != null && syncSource.getInfo().getPreferredType() != null) {
			typeValue.setSelectedItem(syncSource.getInfo().getPreferredType().getType());
		}

		if (this.syncSource.getSourceURI() != null) {
			sourceURIValue.setEditable(false);
		}
		
		dbURLValue.setText(syncSource.getDatabaseURL());
		dbUsernameValue.setText(syncSource.getDatabaseUsername());
		dbPasswordValue.setText(syncSource.getDatabasePassword());
	}

	/**
	 * 
	 * @throws IllegalArgumentException
	 */
	private void validateValues() throws IllegalArgumentException {
		String value = null;

		value = nameValue.getText();
		if (StringUtils.isEmpty(value)) {
			throw new IllegalArgumentException("Field 'Name' cannot be empty. Please provide a SyncSource name.");
		}

		if (!StringUtils.containsOnly(value, NAME_ALLOWED_CHARS.toCharArray())) {
			throw new IllegalArgumentException("Only the following characters are allowed for field 'Name':\n" + NAME_ALLOWED_CHARS);
		}

		value = sourceURIValue.getText();
		if (StringUtils.isEmpty(value)) {
			throw new IllegalArgumentException("Field 'Source URI' cannot be empty. Please provide a SyncSource URI.");
		}
	}

	/**
	 * 
	 */
	private void getValues() {
		ContentType[] contentTypes;
		
		syncSource.setSourceURI(sourceURIValue.getText().trim());
		syncSource.setName(nameValue.getText().trim());

		// Will be redefined in SOGoSyncSource: init()
		contentTypes = new ContentType[] { new ContentType(
				(String)typeValue.getSelectedItem(), "1")
		};

		syncSource.setInfo(new SyncSourceInfo(contentTypes, 0));
		syncSource.setDatabaseURL(dbURLValue.getText().trim());
		syncSource.setDatabaseUsername(dbUsernameValue.getText().trim());
		syncSource.setDatabasePassword(new String(dbPasswordValue.getPassword()));
	}

}
