import os
from flask import Flask, Response, jsonify, render_template, request
from bot_manager import BotManager
from data_service import DataService


def create_app(bot_manager=None, data_service=None):
    app = Flask(__name__)
    bm = bot_manager or BotManager()
    ds = data_service or DataService()

    @app.route("/")
    def index():
        return render_template("index.html")

    # Bot Management APIs
    @app.route("/api/bot/status", methods=["GET"])
    def get_status():
        return jsonify(bm.get_status())

    @app.route("/api/bot/start", methods=["POST"])
    def start_bot():
        data = request.get_json(silent=True) or {}
        adb_port = data.get("adb_port")
        if bm.is_running():
            return jsonify({"success": False, "error": "Bot is already running"}), 400

        started = bm.start(adb_port=adb_port)
        if started:
            return jsonify({"success": True, "message": "Bot started successfully"})
        return jsonify({"success": False, "error": "Failed to launch bot process"}), 500

    @app.route("/api/bot/stop", methods=["POST"])
    def stop_bot():
        if not bm.is_running():
            return jsonify({"success": False, "error": "Bot is not running"}), 400

        stopped = bm.stop()
        if stopped:
            return jsonify({"success": True, "message": "Bot stopped successfully"})
        return jsonify({"success": False, "error": "Failed to terminate bot"}), 500

    @app.route("/api/bot/logs/stream")
    def stream_logs():
        return Response(bm.stream_logs(), mimetype="text/event-stream")

    # Config APIs
    @app.route("/api/config/<name>", methods=["GET"])
    def get_config(name):
        cfg = ds.get_config(name)
        return jsonify(cfg)

    @app.route("/api/config/<name>", methods=["POST"])
    def save_config(name):
        data = request.get_json(silent=True)
        if data is None or not isinstance(data, dict):
            return jsonify({"success": False, "error": "Invalid JSON body"}), 400

        ok, err = ds.save_config(name, data)
        if ok:
            return jsonify({"success": True, "message": f"Config '{name}' saved successfully"})
        return jsonify({"success": False, "error": err}), 400

    # Data & Memory APIs
    @app.route("/api/data/training", methods=["GET"])
    def get_training():
        limit = request.args.get("limit", default=50, type=int)
        offset = request.args.get("offset", default=0, type=int)
        return jsonify(ds.get_training_data(limit=limit, offset=offset))

    @app.route("/api/data/unhandled", methods=["GET"])
    def get_unhandled():
        return jsonify(ds.get_unhandled_questions())

    @app.route("/api/data/memory", methods=["GET"])
    def get_memory():
        search = request.args.get("search", default=None, type=str)
        return jsonify(ds.get_learned_memory(search=search))

    @app.route("/api/data/memory/learn", methods=["POST"])
    def learn_question():
        data = request.get_json(silent=True) or {}
        question = data.get("question")
        answer = data.get("answer")
        if not question or answer is None:
            return jsonify({"success": False, "error": "Both 'question' and 'answer' are required"}), 400

        ok = ds.learn_question(str(question).strip(), str(answer).strip())
        if ok:
            return jsonify({"success": True, "message": "Memory updated"})
        return jsonify({"success": False, "error": "Failed to update memory"}), 500

    @app.route("/api/data/applied", methods=["GET"])
    def get_applied():
        return jsonify(ds.get_applied_jobs())

    @app.route("/api/data/llama-logs", methods=["GET"])
    def get_llama_logs():
        lines = request.args.get("lines", default=100, type=int)
        return jsonify({"logs": ds.get_llama_logs(tail_lines=lines)})

    return app


if __name__ == "__main__":
    app = create_app()
    app.run(host="0.0.0.0", port=5000, debug=True)
