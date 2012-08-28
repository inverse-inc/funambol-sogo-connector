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

public class SOGoKey {

	/**
	 * This method is used to replace keys starting with ./
	 * and sent from various devices with .slash as those
	 * will break in SOGo. We CAN NOT URL-encode those instead
	 * since it'll break in Lightning. Ahh.. much joy.
	 * 
	 * @param s
	 * @return
	 */
	public static String encodeString(String s) {
		if (s.startsWith("./")) {
			return ".slash" + s.substring(2);
		}
		return s;
	}

	/**
	 * 
	 * @param s
	 * @return
	 */
	public static String decodeString(String s) {
		if (s.startsWith(".slash")) {
			return "./" + s.substring(6);
		}
		return s;
	}
}
