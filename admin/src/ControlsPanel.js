import React from "react";
import { ControlsTable } from "./ControlsTable";
import { BACKEND_API_URL } from "./config";

export default class ControlsPanel extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            loaded: false,
            items: [{ text: "Scoreboard", path: "/scoreboard", active: false },
                { text: "Queue", path: "/queue", active: false },
                { text: "Ticker", path: "/ticker", active: false }] };
    }

    async update() {
        const newItems = await Promise.all(
            this.state.items.map(async (element) => {
                const result = await fetch(BACKEND_API_URL + element.path);
                try {
                    return { ...element, active: await result.json() };
                } catch {
                    return { ...element };
                }
            })
        );
        this.setState({ items: newItems, loaded: true });
        console.log("update:",this.state.items);
    }

    async componentDidMount() {
        await this.update();
    }

    render() {
        console.log("render:",this.state.items);
        return (
            <div>
                { this.state.loaded && <ControlsTable
                    key="controls table"
                    updateTable={() => {this.update();}}
                    activeColor={this.props.activeColor}
                    inactiveColor={this.props.inactiveColor}
                    items={this.state.items}
                    headers={["Text"]}
                    keys={["text"]}/>
                }
            </div>
        );
    }
}

