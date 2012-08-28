/*
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

import com.funambol.common.pim.calendar.Calendar;
import com.funambol.common.pim.converter.ConverterException;
import com.funambol.common.pim.converter.VCalendarConverter;
import com.funambol.common.pim.converter.VComponentWriter;
import com.funambol.common.pim.icalendar.ICalendarParser;
import com.funambol.common.pim.icalendar.ParseException;
import com.funambol.common.pim.model.VCalendar;
import com.funambol.common.pim.model.VComponent;
import com.funambol.framework.engine.SyncItem;
import com.funambol.framework.logging.FunambolLogger;
import com.funambol.framework.logging.FunambolLoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.TimeZone;

/**
 * Class for holding the various representations of calendar data that are used by the connector.
 *
 * @author Håvard Wigtil
 */
public class CalendarData {

    /** Logger instance. */
    private static final FunambolLogger log = FunambolLoggerFactory.getLogger("funambol.sogo");

    /** Calendar data as Funambol Calendar. */
    private Calendar calendar;

    /** Calendar data as parsed iCalendar. */
    private VCalendar vcalendar;

    /** Calendar data as iCalendar string. */
    private String content;

    /**
     * Create a new empty instance.
     */
    public CalendarData() {
        this.calendar = new Calendar();
        this.vcalendar = new VCalendar();
        this.content = "";
    }

    /**
     * Initialize all data variants from a SyncItem. If the data in SyncItem is vCalendar, then
     * it will be converted to iCalendar for <code>vcalendar</code> and <code>content</code>.
     *
     * @param item The item to get data from.
     * @param tz Time zone for data.
     * @param charset Character set for data.
     * @throws ParseException Exceptions from iCalendar parsing
     * @throws ConverterException Exceptions from converting between formats
     */
    public CalendarData(SyncItem item, TimeZone tz, String charset) throws ParseException, ConverterException {
        ICalendarParser parser;
        VCalendarConverter converter;

        content = SOGoSanitizer.sanitizevCalendarInput(item.getContent(), item.getKey().getKeyAsString(), log);

        parser = new ICalendarParser(new ByteArrayInputStream(content.getBytes()));

        vcalendar = parser.ICalendar();

        converter = new VCalendarConverter(tz, charset, true);
        calendar = converter.vcalendar2calendar(vcalendar);

        if (calendar.getVersion().getPropertyValueAsString().equals("1.0")) {
            VComponentWriter writer;


            log.info("Converting vCalendar to iCalendar");

            vcalendar = convertVcalendarToIcalendar(vcalendar, converter, calendar, log);

            writer = new VComponentWriter(VComponentWriter.NO_FOLDING);
            content = writer.toString(vcalendar);
        }

    }

    /**
     * Convert a vCalendar object to iCalendar format.
     *
     *
     * @param vcal Original vCalendar
     * @param converter Converter object to use
     * @param calendar Calendar object corresponding to VCalendar object
     * @param log Logger instance
     * @return The input object in iCalendar format
     * @throws ConverterException Thrown if the conversion fails
     */
    private static VCalendar convertVcalendarToIcalendar(VCalendar vcal, VCalendarConverter converter, Calendar calendar, FunambolLogger log) throws ConverterException {
        VCalendar v2;

        SOGoSanitizer.sanitizeFunambolCalendar(calendar);

        v2 = converter.calendar2vcalendar(calendar, false);

        // Non-standard properties are not copied by Funambol, so we must do a manual copy
        SOGoUtilities.copyCustomProperties(vcal.getVCalendarContent(), v2.getVCalendarContent());

        // Alarm properties have some problems
        // This is filed as http://forge.ow2.org/tracker/?group_id=96&atid=100096&func=detail&aid=314853
        VComponent valarm = v2.getVCalendarContent().getComponent("VALARM");
        if (valarm != null) {
            com.funambol.common.pim.model.Property repeat, action, desc;

            log.info("Adding work-around for VALARM");

            // Remove repeat if set to 0
            repeat = valarm.getProperty("REPEAT");
            if (repeat != null && "0".equals(repeat.getValue())) {
                valarm.delProperty(repeat);
                log.info("Removed REPEAT:0 from VALARM");
            }

            // ACTION is required by the standard, but not added by Funambol
            action = valarm.getProperty("ACTION");
            if (action == null) {
                action = new com.funambol.common.pim.model.Property("ACTION", "DISPLAY");
                valarm.addProperty(action);
                // Action with value DISPLAY must have a description according to the standard
                desc = valarm.getProperty("DESCRIPTION");
                if (desc == null) {
                    String descText = v2.getVCalendarContent().getProperty("SUMMARY").getValue();
                    desc = new com.funambol.common.pim.model.Property("DESCRIPTION", descText);
                    valarm.addProperty(desc);
                }
            }

        }

        return v2;
    }

    /**
     * Get calendar data as Funambol Calendar object.
     *
     * @return Calendar object
     */
    public Calendar getCalendar() {
        return calendar;
    }

    /**
     * Get calendar data as VCalendar object.
     *
     * @return VCalendar object
     */
    public VCalendar getVcalendar() {
        return vcalendar;
    }

    /**
     *
     * @return
     */
    public String getContent() {
        return content;
    }
}
