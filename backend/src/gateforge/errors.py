from __future__ import annotations


class GateForgeError(Exception):
    def __init__(self, what: str, fix: str = "", status_code: int = 400) -> None:
        super().__init__(what)
        self.what = what
        self.fix = fix
        self.status_code = status_code


class NotFoundError(GateForgeError):
    def __init__(self, resource: str) -> None:
        super().__init__(f"{resource}不存在", "刷新数据并检查 ID", 404)


class QualityGateError(GateForgeError):
    def __init__(self, what: str, fix: str) -> None:
        super().__init__(what, fix, 409)

