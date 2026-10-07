#!/usr/bin/env python3
import argparse
import sys
from app import create_app

def main():
    parser = argparse.ArgumentParser(description="JobStreet Auto-Applier Web Dashboard")
    parser.add_argument("--host", default="0.0.0.0", help="Host address to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=5000, help="Port to listen on (default: 5000)")
    parser.add_argument("--debug", action="store_true", help="Run Flask in debug mode")

    args = parser.parse_args()

    print("==================================================")
    print("    JobStreet Auto-Applier Web Dashboard          ")
    print(f"    Available locally at: http://localhost:{args.port}")
    print(f"    Available on LAN at:  http://<phone-ip>:{args.port}")
    print("==================================================")

    app = create_app()
    app.run(host=args.host, port=args.port, debug=args.debug)

if __name__ == "__main__":
    main()
