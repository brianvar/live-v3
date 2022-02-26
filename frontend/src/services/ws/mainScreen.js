import { pushLog } from "../../redux/debug";
import { hideWidget, setWidgets, showWidget } from "../../redux/widgets";

export const handleMessage = (dispatch) => (e) => {
    const message = JSON.parse(e.data);
    dispatch(pushLog(JSON.stringify(message)));
    switch (message.type) {
    case "ShowWidget":
        dispatch(showWidget(message.widget));
        break;
    case "HideWidget":
        dispatch(hideWidget(message.id));
        break;
    case "MainScreenSnapshot":
        dispatch(setWidgets(message.widgets));
        break;
    default:
        dispatch(pushLog(`UNKNOWN MESSAGE TYPE: ${message.type}`));
        break;
    }
};
