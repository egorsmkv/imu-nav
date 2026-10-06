"""Disposable local PostgreSQL for traffic simulations; never accepts a production URL."""

import json
import subprocess
import time


class Postgres:
    def __init__(self, runtime=None):
        self.runtime_args = ["--runtime", runtime] if runtime else []

    def __enter__(self):
        self.container = subprocess.check_output([
            "docker", "run", "--rm", "-d", *self.runtime_args, "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
            "-p", "127.0.0.1::5432", "postgres:18-bookworm"], text=True).strip()
        try:
            port = subprocess.check_output(["docker", "port", self.container, "5432"], text=True).strip().split(":")[-1]
            self.url = f"postgresql://postgres@127.0.0.1:{port}"
            for _ in range(60):
                if subprocess.run(["docker", "exec", self.container, "pg_isready", "-h", "127.0.0.1", "-U", "postgres"],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
                    return self
                time.sleep(.5)
            raise TimeoutError("disposable PostgreSQL did not become ready")
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, *_):
        subprocess.run(["docker", "stop", self.container], check=True, stdout=subprocess.DEVNULL)

    def database(self, directory):
        name = directory.name.replace("-", "_")
        subprocess.run(["docker", "exec", self.container, "createdb", "-U", "postgres", name], check=True)
        config = directory / "database.toml"
        config.write_text(f'[database]\nbackend = "postgres"\nurl = "{self.url}/{name}?sslmode=disable"\n')
        return config

    def query(self, directory, sql):
        name = directory.name.replace("-", "_")
        output = subprocess.check_output([
            "docker", "exec", self.container, "psql", "-U", "postgres", "-d", name, "-At", "-c",
            f"SELECT row_to_json(row) FROM ({sql}) row"], text=True)
        return [tuple(json.loads(line).values()) for line in output.splitlines()]

    def usage(self):
        # Includes database background work and the small cost of these docker-exec probes.
        output = subprocess.check_output(["docker", "exec", self.container, "cat",
                                          "/sys/fs/cgroup/cpu.stat", "/sys/fs/cgroup/memory.peak"], text=True)
        lines = output.splitlines()
        cpu = next(int(line.split()[1]) for line in lines if line.startswith("usage_usec "))
        return {"cpu_ms": cpu / 1000, "peak_memory_bytes": int(lines[-1])}
