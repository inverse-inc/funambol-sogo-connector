package ca.inverse.sogo.engine.source;

import com.funambol.framework.server.Sync4jUser;

public class SOGoUser extends Sync4jUser {

	private static final long serialVersionUID = 1L;
	private String userID;
	
	public void setUserID(String s) {
		this.userID = s;
	}
	
	public String getUserID() {
		return this.userID;
	}
}
