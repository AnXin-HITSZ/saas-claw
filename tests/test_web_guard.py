import unittest
from unittest.mock import patch

from openclaw.tools.web.ssrf_guard import SsrfGuardError, validate_public_http_url


class WebGuardTests(unittest.TestCase):
    def test_blocks_localhost(self):
        with self.assertRaises(SsrfGuardError):
            validate_public_http_url("http://localhost:8000")

    def test_blocks_private_ip(self):
        with patch("socket.getaddrinfo", return_value=[(None, None, None, None, ("192.168.1.10", 80))]):
            with self.assertRaises(SsrfGuardError):
                validate_public_http_url("http://example.test")

    def test_blocks_embedded_credentials(self):
        with self.assertRaises(SsrfGuardError):
            validate_public_http_url("https://user:pass@example.com")

    def test_allows_public_ip(self):
        with patch("socket.getaddrinfo", return_value=[(None, None, None, None, ("93.184.216.34", 80))]):
            self.assertEqual(validate_public_http_url("https://example.com"), "https://example.com")


if __name__ == "__main__":
    unittest.main()