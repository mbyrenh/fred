package freenet.client.updaters;

import com.google.gwt.user.client.ui.RootPanel;

import freenet.client.FreenetJs;

public class ReplacerUpdater implements IUpdater {

	@Override
	public void updated(String elementId, String content) {
		FreenetJs.log("Replacing element id:" + elementId + " with content:" + content + " element:" + RootPanel.get(elementId));
		if (RootPanel.get(elementId) != null) {
			FreenetJs.log("element.getElement():" + RootPanel.get(elementId).getElement() + " current innerHTML:" + RootPanel.get(elementId).getElement().getInnerHTML());
		}
		try {
			RootPanel.get(elementId).getElement().setInnerHTML(content);
		} catch (Exception e) {
			FreenetJs.log("Error when setting html" + e.toString());
		}
		FreenetJs.log("content after update:" + RootPanel.get(elementId).getElement().getInnerHTML());
	}

}
