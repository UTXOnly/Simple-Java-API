import time
import requests
import threading
import ddtrace
from ddtrace import tracer

tracer.configure(hostname='127.0.0.1', port=8126)

# ddtrace.patch_all()

def fetch():
    url = 'http://localhost:8000/fetch'
    while True:
        try:
            response = requests.get(url, timeout=5)
            print(response.text)
            response.raise_for_status()
            assert response.status_code == 200, f'Request failed with status code {response.status_code}'
        except requests.exceptions.RequestException as e:
            print(f'Error making request: {e}')
        time.sleep(1)


def test_make_requests():
    url = 'http://localhost:8000/query'
    while True:
        try:
            response = requests.get(url, timeout=5)
            print(response.text)
            response.raise_for_status()
            assert response.status_code == 200, f'Request failed with status code {response.status_code}'
        except requests.exceptions.RequestException as e:
            print(f'Error making request: {e}')
        #time.sleep(1)

if __name__ == '__main__':
    fetch_thread = threading.Thread(target=fetch)
    fetch_thread.start()

    test_thread = threading.Thread(target=test_make_requests)
    test_thread.start()