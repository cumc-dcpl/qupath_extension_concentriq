import argparse
import sys

import requests

ANALYSIS_APPS_PATH = "/ias/v1/analysisApps"

# Fixed values from the request body
APP_VERSION = "1.0.0"
APP_ALGORITHM = "analysis_results_collection"


def create_analysis_app(
    concentriq_url: str,
    vendor_api_key: str,
    analysis_app_key_name: str,
    analysis_app_human_readable_name: str,
) -> requests.Response:
    """POST a new analysis app to Concentriq."""
    url = f"{concentriq_url.rstrip('/')}{ANALYSIS_APPS_PATH}"

    headers = {
        "concentriq-api-key": vendor_api_key,
        "Content-Type": "application/json",
    }

    body = {
        "key": analysis_app_key_name,
        "name": analysis_app_human_readable_name,
        "version": APP_VERSION,
        "algorithm": APP_ALGORITHM,
        "triggerableByUser": False,
    }

    return requests.post(url, json=body, headers=headers, timeout=30)


def main():
    parser = argparse.ArgumentParser(
        description="Creates a Concentriq analysis app."
    )

    parser.add_argument(
        "--vendor_api_key", required=True, help="The API key for the vendor"
    )
    parser.add_argument(
        "--concentriq_url",
        required=True,
        help="The Concentriq base URL, e.g. https://cumc-dcpl.concentriq.proscia.com",
    )
    parser.add_argument(
        "--analysis_app_key_name",
        required=True,
        help="The key for the analysis app, e.g. analysis-results-collection-1.0.0",
    )
    parser.add_argument(
        "--analysis_app_human_readable_name",
        required=True,
        help="The display name for the analysis app, e.g. 'Analysis Result Submission'",
    )

    args = parser.parse_args()

    try:
        response = create_analysis_app(
            args.concentriq_url,
            args.vendor_api_key,
            args.analysis_app_key_name,
            args.analysis_app_human_readable_name,
        )
        response.raise_for_status()
    except requests.HTTPError as e:
        print(
            f"Request failed: {e}\nResponse body: {e.response.text}",
            file=sys.stderr,
        )
        sys.exit(1)
    except requests.RequestException as e:
        print(f"Request failed: {e}", file=sys.stderr)
        sys.exit(1)

    try:
        app = response.json()
    except ValueError:
        print(
            f"Unexpected non-JSON response (HTTP {response.status_code}):\n"
            f"{response.text}",
            file=sys.stderr,
        )
        sys.exit(1)

    print("Analysis app created successfully:")
    print(f"  Name: {app.get('name')}")
    print(f"  ID:   {app.get('id')}")


if __name__ == "__main__":
    main()