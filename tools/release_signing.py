#!/usr/bin/env python3
"""Sign direct-download release APKs using only the durable production release identity."""
from benchmark_signing import main


if __name__ == '__main__':
    main(profile='release')
