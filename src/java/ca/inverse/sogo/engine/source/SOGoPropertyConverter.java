/* -*- Mode: java; tab-width: 2; c-tab-always-indent: t; indent-tabs-mode: t; c-basic-offset: 2 -*- */
/* 
 * Copyright (c) 2004 Harrie Hazewinkel. All rights reserved.
 */

/*
 * Copyright (C) 2006-2007 Funambol, Inc.
 *
 * Copies of this file are distributed by Funambol as part of server-side
 * programs (such as Funambol Data Synchronization Server) installed on a
 * server and also as part of client-side programs installed on individual
 * devices.
 *
 * The following license notice applies to copies of this file that are
 * distributed as part of server-side programs:
 *
 * Copyright (C) 2006-2007 Funambol, Inc.
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
 * The following license notice applies to copies of this file that are
 * distributed as part of client-side programs:
 *
 * Copyright (C) 2006-2007 Funambol, Inc.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY, TITLE, NONINFRINGEMENT or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
 * 02111-1307  USA
 */
package ca.inverse.sogo.engine.source;

import com.funambol.common.pim.common.ConversionException;
import com.funambol.common.pim.model.Property;
import com.funambol.common.pim.model.VComponent;
import org.w3c.dom.*;
import org.apache.commons.lang.StringEscapeUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import com.funambol.common.pim.common.PropertyConverter;

/**
 *
 * @version $Id: S4jPropertyConverter.java,v 1.3 2007/06/18 12:40:59 luigiafassina Exp $
 * 
 * Class renamed to SOGoPropertyConverter to avoid clashes.
 */
@SuppressWarnings(value={"unchecked"})
public class SOGoPropertyConverter implements PropertyConverter {
    protected static final String SIF2ICAL_MAPPINGS_RESOURCE = "S4j2ICalMapping.properties";
    protected static final String ICAL2SIF_MAPPINGS_RESOURCE = "ICal2S4jMapping.properties";
    protected static final String DEFAULT_X_PREFIX = "X-S4J-";
    protected static final String PROP_PREFIX = "prop.";
    protected static final String VAL_PREFIX = "value.";

    private Map sif2ICalDirectMappings;
    private Map iCal2SifDirectMappings;

    private Map sif2ICalDependentMappings;
    private Map iCal2SifDependentMappings;

    private VComponent ownerComponent;
    private String componentType;

    public SOGoPropertyConverter ( VComponent ownerComponent ) throws IOException {
        this.ownerComponent = ownerComponent;
        this.componentType  = ownerComponent.getSifType ();
        initMappings ();
    }

    /**
     * This method converts DOM element to Property instance. Multiple SIF properties may map
     * into one iCalendar property, so some of the properties just 'skipped' when conversion is performed,
     * because they should be used only in conjunction with other properties. When SIF property is skipped,
     * implementation will return <b>null</b>.
     *
     * @param o instance of org.w3c.dom.Element representing SIF property
     * @return Cal4j Property instance if conversion was successful and SIF element was not skipped
     */
    public Property convertDataToProperty ( Object o ) throws ConversionException {
        if ( ! ( o instanceof Element ) )
            throw new IllegalArgumentException ( "argument must be instance of org.w3c.Element" );

        String sifPropertyValue;
        String sifPropertyName;
        String iCalPropertyName;
        String iCalPropertyValue;

        Element sifElement = (Element) o;
        Node sifValueNode = sifElement.getFirstChild ();

        sifPropertyName = sifElement.getTagName ();

        if ( sifValueNode != null && ( sifValueNode instanceof Text ) ) {
            Text textNode = (Text) sifValueNode;
            sifPropertyValue = textNode.getNodeValue ();

            if ( sifPropertyMappedDirectly ( sifPropertyName ) ) {
                /* perform appropriate mapping of property name & value */
                String keyName = this.componentType + '.' + PROP_PREFIX + sifPropertyName;
                String valName = this.componentType + '.' + VAL_PREFIX + sifPropertyName + "." + sifPropertyValue;
                iCalPropertyName = (String) this.sif2ICalDirectMappings.get ( keyName );
                iCalPropertyValue = (String) this.sif2ICalDirectMappings.get ( valName );
                if ( iCalPropertyValue == null )
                    iCalPropertyValue = sifPropertyValue;
                return new Property ( iCalPropertyName, iCalPropertyValue );
            } else if ( sifPropertyHasDependencies ( sifPropertyName ) ) {
                /* process this property and all dependent properties */
                try {
                    String methodName = "_convert" + sifPropertyName;
                    Method[] methods = getClass ().getDeclaredMethods ();
                    for ( int i = 0; i < methods.length; i++ ) {
                        if ( methods[i].getName ().equals ( methodName ) ) {
                            methods[i].setAccessible ( true );
                            return (Property) methods[i].invoke ( this, new Object[] { sifElement } );
                        }
                    }
                    return null;
                } catch ( IllegalAccessException e ) {
                    throw new ConversionException ( e );
                } catch ( InvocationTargetException e ) {
                    throw new ConversionException ( e.getTargetException () );
                }
            } else if ( sifPropertyIsDependent ( sifPropertyName ) ) {
                /* skip property */
                return null;
            } else {
                /* create x-property */
                iCalPropertyName = DEFAULT_X_PREFIX + sifPropertyName;
                iCalPropertyValue = sifPropertyValue;
                return new Property ( iCalPropertyName, iCalPropertyValue );
            }
        } else {
            return null;
        }
    }

    public Object convertPropertyToData ( Property p ) throws ConversionException {
        String sifTag = "";
        String propertyName = p.getName ();
        String propertyValue = p.getValue ();

        if ( iCalPropertyMappedDirectly ( propertyName ) ) {
            String keyName = this.componentType + '.' + PROP_PREFIX + propertyName;
            String valName = this.componentType + '.' + VAL_PREFIX + propertyName + "." + propertyValue;
            String sifTagName = (String) iCal2SifDirectMappings.get ( keyName );
            String sifTagValue = (String) iCal2SifDirectMappings.get ( valName );
            if ( sifTagValue == null ) sifTagValue = propertyValue;
            sifTag = createSifTag ( sifTagName,  sifTagValue );
        } else if ( iCalPropertyHasDependencies ( propertyName ) ) {
            try {
                String methodName = "_convert" + propertyName;
                Method[] methods = getClass ().getDeclaredMethods ();
                for ( int i = 0; i < methods.length; i++ ) {
                    if ( methods[i].getName ().equals ( methodName ) ) {
                        methods[i].setAccessible ( true );
                        return (String) methods[i].invoke ( this, new Object[] { p } );
                    }
                }
            } catch ( IllegalAccessException e ) {
                throw new ConversionException ( e );
            } catch ( InvocationTargetException e ) {
                throw new ConversionException ( e.getTargetException () );
            }
        } else if ( propertyName.toUpperCase ().startsWith ( DEFAULT_X_PREFIX ) ) {
            String sifTagName = propertyName.substring ( DEFAULT_X_PREFIX.length () );
            sifTag = createSifTag ( sifTagName,  propertyValue );
        } else {
            sifTag = createSifTag ( propertyName.toLowerCase (), propertyValue );
        }

        return sifTag;
    }

    private boolean sifPropertyIsDependent ( String sifPropertyName ) {
        Set entrySet = this.sif2ICalDependentMappings.entrySet ();
        for ( Iterator iter = entrySet.iterator (); iter.hasNext (); ) {
            Map.Entry entry = (Map.Entry) iter.next ();
            List list = (List) entry.getValue ();
            if ( list.contains ( sifPropertyName ) ) return true;
        }

        return false;
    }

    private boolean sifPropertyHasDependencies ( String sifPropertyName ) {
        return this.sif2ICalDependentMappings.containsKey ( sifPropertyName );
    }

    private boolean sifPropertyMappedDirectly ( String sifPropertyName ) {
        String key = this.componentType + '.' + PROP_PREFIX + sifPropertyName;
        String val = (String) this.sif2ICalDirectMappings.get ( key );

        return ( ( val != null ) && ! "".equals ( val ) );
    }

    private boolean iCalPropertyMappedDirectly ( String iCalPropertyName ) {
        String val = (String) this.iCal2SifDirectMappings.get ( this.componentType + '.' + PROP_PREFIX + iCalPropertyName );
        return ( ( val != null ) && ! "".equals ( val ) );
    }

    private boolean iCalPropertyHasDependencies ( String iCalPropertyName ) {
        return this.iCal2SifDependentMappings.containsKey ( iCalPropertyName );
    }

    private void initMappings () throws IOException {
        Properties mappings = new java.util.Properties ();
        mappings.load ( getClass ().getResourceAsStream ( SIF2ICAL_MAPPINGS_RESOURCE ) );
        this.sif2ICalDirectMappings = (Map) mappings.clone ();
        mappings.load ( getClass ().getResourceAsStream ( ICAL2SIF_MAPPINGS_RESOURCE ) );
        this.iCal2SifDirectMappings = (Map) mappings.clone ();

        Map dependencies = new Hashtable ();
        dependencies.put ( "IsRecurring",
                Arrays.asList ( new String[] {
                        "RecurrenceType",       // just a hint: should be processed first
                        "Interval",
                        "MonthOfYear",
                        "DayOfMonth",
                        "DayOfWeekMask",
                        "Instance",
                        "PatternStartDate",
                        "NoEndDate",
                        "PatternEndDate",
                        "Occurrences",
                } )
        );
        dependencies.put ( "ReminderSet",
                Arrays.asList ( new String[] {
                        "ReminderMinutesBeforeStart",
                        "ReminderSoundFile",
                        "ReminderOptions",
                } )
        );
        this.sif2ICalDependentMappings = dependencies;

        this.iCal2SifDependentMappings = new Hashtable ();
        this.iCal2SifDependentMappings.put ( "RRULE", Boolean.TRUE );
    }

    /* conversion for inter-dependent properties  */
    protected String _convertRRULE ( Property p ) {
        StringBuffer buffer = new StringBuffer ();
        buffer.append ( createSifTag ( "IsRecurring", "1" ) );

        String propertyValue = p.getValue ();
        String[] rRuleParams = propertyValue.split ( ";" );

        int olRecurrenceType = -1;
        int olInterval = 0;
        int olInstance = 0;
        int olDayOfWeekMask = 0;
        int olDayOfMonth = 0;
        int olMonthOfYear = 0;
        String olPatternEndDate = null;
        String olOccurrences = null;

        for ( int i = 0; i < rRuleParams.length; i++ ) {
            String[] params = rRuleParams[i].split ( "=" );
            String key = params[0];
            String val = params[1];

            if ( key.equals ( "FREQ" ) ) {
                String sifValue = (String) iCal2SifDirectMappings.get ( "rrule." + key + '.' + val );
                olRecurrenceType = Integer.valueOf ( sifValue ).intValue ();
            }

            if ( key.equals ( "INTERVAL" ) ) {
                olInterval = Integer.valueOf ( val ).intValue ();
            }

            if ( key.equals ( "UNTIL" ) ) olPatternEndDate = val;

            if ( key.equals ( "COUNT" ) ) olOccurrences = val;

            if ( key.equals ( "BYDAY" ) ) {
                int dayOfWeekMask = 0;
                String[] dayNames = val.split ( "," );
                for ( int j = 0; j < dayNames.length; j++ ) {
                	String name = dayNames[j];
                	// We might have something like this in the RRULE:
                	// RRULE:INTERVAL=1;FREQ=WEEKLY;BYDAY=SU\,MO
                	// The "\," will cause issues.
                	name = name.replace("\\", "");
                    String dayMaskValue = (String) iCal2SifDirectMappings.get ( "rrule." + key + '.' + name );
                    
                    // We make this check as we might have something like this: BYDAY=3SU  or BYDAY=1MO
                    // The "3SU" isn't contained in our property file.
                    if (dayMaskValue != null) {
                    	dayOfWeekMask |= Integer.valueOf ( dayMaskValue ).intValue ();
                    }
                }
                if ( dayOfWeekMask > 0 ) {
                    olDayOfWeekMask = dayOfWeekMask;
                }
            }

            if ( key.equals ( "BYSETPOS" ) ) {
                if ( val.indexOf ( ',' ) != -1 ) {
                    olInstance = Integer.valueOf ( val ).intValue ();
                    if ( olRecurrenceType == 2 ) olRecurrenceType = 3;
                    if ( olRecurrenceType == 5 ) olRecurrenceType = 6;
                } else {
                    olDayOfMonth = Integer.valueOf ( val ).intValue ();
                }
            }

            if ( key.equals ( "BYMONTH" ) )
                olMonthOfYear = Integer.valueOf ( val ).intValue ();
        }


        buffer.append ( createSifTag ( "RecurrenceType", String.valueOf ( olRecurrenceType ) ) );
        buffer.append ( createSifTag ( "Interval", String.valueOf ( olInterval ) ) );
        buffer.append ( createSifTag ( "Instance", String.valueOf ( olInstance ) ) );
        buffer.append ( createSifTag ( "DayOfWeekMask", String.valueOf ( olDayOfWeekMask ) ) );
        buffer.append ( createSifTag ( "DayOfMonth", String.valueOf ( olDayOfMonth ) ) );
        buffer.append ( createSifTag ( "MonthOfYear", String.valueOf ( olMonthOfYear ) ) );

        if (this.ownerComponent.getProperty ( "DTSTART" ) != null) {
        	buffer.append ( createSifTag ( "PatternStartDate", this.ownerComponent.getProperty ( "DTSTART" ).getValue () ) );
        }

        if ( olPatternEndDate != null ) {
            buffer.append ( createSifTag ( "PatternEndDate", olPatternEndDate ) );
            buffer.append ( createSifTag ( "NoEndDate", "0" ) );
        } else {
            buffer.append ( createSifTag ( "PatternEndDate", "") );
            buffer.append ( createSifTag ( "NoEndDate", "1" ) );
        }

        if ( olOccurrences != null )
            buffer.append ( createSifTag ( "Occurrences", olOccurrences ) );

        return buffer.toString ();
    }

    protected Property _convertIsRecurring ( Element e ) {
        Text textNode = (Text) e.getFirstChild ();
        String isRecurringValue = textNode.getNodeValue ();
        if ( ! Boolean.valueOf ( isRecurringValue ).booleanValue () &&
                Integer.valueOf ( isRecurringValue ).intValue () < 1 )
            return null;

        Element rootElement = e.getOwnerDocument ().getDocumentElement ();
        StringBuffer propertyValue = new StringBuffer ();

        String sifRecurrenceType = getSifValue ( rootElement, "RecurrenceType" );
        if ( sifRecurrenceType != null ) {
            String iCalRecurrenceType = (String)
                    this.sif2ICalDirectMappings.get ( "rrule.RecurrenceType." + sifRecurrenceType );
            propertyValue.append ( "FREQ=" ).append ( iCalRecurrenceType );

            String patternEndDate = getSifValue ( rootElement, "PatternEndDate" );
						if ( patternEndDate == null ) {
							String occurences = getSifValue ( rootElement, "Occurrences" );
							if ( occurences != null && ( Integer.valueOf ( occurences ).intValue () > 0 ) )
                propertyValue.append ( ";COUNT=" ).append ( occurences );
						} else {

//             if ( patternEndDate != null )
                propertyValue.append ( ";UNTIL=" ).append ( patternEndDate );
						}

//             boolean noEndDate = "1".equals ( getSifValue ( rootElement, "NoEndDate" ) );

            int interval = 0, dayOfWeekMask = 0, dayOfMonth = 0, instance = 0, monthOfYear = 0;

            switch ( Integer.valueOf ( sifRecurrenceType ).intValue () ) {
                // olRecursDaily
                case 0:
                    interval        = Integer.valueOf ( getSifValue ( rootElement, "Interval" ) ).intValue ();
                    break;
                // olRecursWeekly
                case 1:
                    interval        = Integer.valueOf ( getSifValue ( rootElement, "Interval" ) ).intValue ();
                    dayOfWeekMask   = Integer.valueOf ( getSifValue ( rootElement, "DayOfWeekMask" ) ).intValue ();
                    break;
                // olRecursMonthly
                case 2:
                    interval        = Integer.valueOf ( getSifValue ( rootElement, "Interval" ) ).intValue ();
                    dayOfWeekMask   = Integer.valueOf ( getSifValue ( rootElement, "DayOfWeekMask" ) ).intValue ();
                    dayOfMonth      = Integer.valueOf ( getSifValue ( rootElement, "DayOfMonth") ).intValue ();
                    break;
                // olRecursMonthNth
                case 3:
                    interval        = Integer.valueOf ( getSifValue ( rootElement, "Interval" ) ).intValue ();
                    instance        = Integer.valueOf ( getSifValue ( rootElement, "Instance" ) ).intValue ();
                    dayOfWeekMask   = Integer.valueOf ( getSifValue ( rootElement, "DayOfWeekMask" ) ).intValue ();
                    break;
                // olRecursYearly
                case 5:
                    monthOfYear     = Integer.valueOf ( getSifValue ( rootElement, "MonthOfYear" ) ).intValue ();
                    dayOfMonth      = Integer.valueOf ( getSifValue ( rootElement, "DayOfMonth") ).intValue ();
                    break;
                // olRecursYearNth
                case 6:
                    instance        = Integer.valueOf ( getSifValue ( rootElement, "Instance" ) ).intValue ();
                    dayOfWeekMask   = Integer.valueOf ( getSifValue ( rootElement, "DayOfWeekMask" ) ).intValue ();
                    monthOfYear     = Integer.valueOf ( getSifValue ( rootElement, "MonthOfYear" ) ).intValue ();
                    break;
            }

            if ( interval > 1 )
                propertyValue.append ( ";INTERVAL=" ).append ( interval );

            if ( instance > 0 )
                propertyValue.append ( ";BYSETPOS=" ).append ( instance > 4 ? -1 : instance );

            if ( dayOfWeekMask > 0 ) {
                StringBuffer byDayBuffer = new StringBuffer ();
                for ( int i = 1; i <= 64; i <<= 1 ) {
                    if ( ( dayOfWeekMask & i ) == i ) {
                        String iCalWkDay = (String) this.sif2ICalDirectMappings.get ( "rrule.DayOfWeekMask." + i );
                        if ( byDayBuffer.length () > 0 ) byDayBuffer.append ( ',' );
                        byDayBuffer.append ( iCalWkDay );
                    }
                }
                if ( byDayBuffer.length () > 0 )
                    propertyValue.append ( ";BYDAY=" ).append ( byDayBuffer );
            }

            if ( monthOfYear > 0 )
                propertyValue.append ( ";BYMONTH=" ).append ( monthOfYear );

            if ( dayOfMonth > 0 )
                propertyValue.append ( ";BYMONTHDAY=" ).append ( dayOfMonth );

        } else {
            /* no recurrence type specified - won't create RRULE */
            return null;
        }

        return new Property ( "RRULE", propertyValue.toString () );
    }

    protected Property _convertReminderSet ( Element e ) {
        return null;
    }

    private String getSifValue ( Element rootElement, String propertyName ) {
        NodeList nodeList = rootElement.getElementsByTagName ( propertyName );
        if ( nodeList.getLength () > 0 ) {
            Element propertyElement = (Element) nodeList.item ( 0 );
            Text propertyText = (Text) propertyElement.getFirstChild ();
            if ( propertyText != null )
                return propertyText.getNodeValue ();
            else
                return null;
        } else {
            return null;
        }
    }

    private String createSifTag ( String name, String value ) {
        return '<' + name + ">" + StringEscapeUtils.escapeXml ( value ) + "</" + name + '>';
    }
}
