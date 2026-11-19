import argparse
import sys

import requests

USER_DEFINED_FIELDS_PATH = "/ias/v1/userDefinedFields"


def create_user_defined_field(
    concentriq_url: str, vendor_api_key: str, key: str, human_readable_field_name: str,
) -> requests.Response:
    """POST a new "Requestor UNI" user defined field for Analysis resources."""
    url = f"{concentriq_url.rstrip('/')}{USER_DEFINED_FIELDS_PATH}"

    headers = {
        "concentriq-api-key": vendor_api_key,
        "Content-Type": "application/json",
    }

    body = {
        "resourceType": "Analysis",
        "key": key,
        "name": human_readable_field_name,
        "jsonSchema": {
            "type": "string",
            "title": human_readable_field_name,
        },
    }

    return requests.post(url, json=body, headers=headers, timeout=30)


def main():
    parser = argparse.ArgumentParser(
        description="Accepts vendor_api_key and concentriq_url arguments."
    )

    parser.add_argument(
        "--vendor_api_key", required=True, help="The API key for the vendor"
    )
    parser.add_argument(
        "--concentriq_url",
        required=True,
        help="The Concentriq base URL, e.g. https://cumc-dcpl-test.concentriq.proscia.com",
    )
    parser.add_argument(
        "--requester_field_key",
        required=True,
        help="The key string for the user defined field",
    )
    parser.add_argument(
        "--requester_field_name",
        required=True,
        help="The human-readable name of the user defined field for capturing the analysis requester",
    )

    args = parser.parse_args()

    try:
        response = create_user_defined_field(
            args.concentriq_url, args.vendor_api_key, args.requester_field_key, args.requester_field_name,
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
        field = response.json()
    except ValueError:
        print(
            f"Unexpected non-JSON response (HTTP {response.status_code}):\n"
            f"{response.text}",
            file=sys.stderr,
        )
        sys.exit(1)

    print("User defined field created successfully:")
    print(f"  Key:        {field.get('key')}")
    print(f"  Name:       {field.get('name')}")
    print(f"  Created At: {field.get('createdAt')}")


if __name__ == "__main__":
    main()