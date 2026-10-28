import hashlib
from pathlib import Path

import pytest
from fakes import FakeAdb

from buckler.app_catalog import CatalogError
from buckler.iconpacks import install_iconpack, load_iconpacks, require


class InstallAdb(FakeAdb):
    def install_apk(self, apk_path: str) -> str:
        self.present.add("app.lawnchair.lawnicons")
        return "Success"


def test_catalog_packs_are_pinned() -> None:
    packs = load_iconpacks()
    assert {p.id for p in packs} >= {"lawnicons", "arcticons"}
    assert all(p.url.startswith("https://") and len(p.sha256) == 64 for p in packs)


def test_unknown_pack_names_the_known_ones() -> None:
    with pytest.raises(CatalogError, match="lawnicons"):
        require(load_iconpacks(), "nope")


def test_install_uses_the_verified_cached_apk(tmp_path: Path) -> None:
    body = b"apk"
    pack = require(load_iconpacks(), "lawnicons")
    pack = type(pack)(**{**pack.__dict__, "sha256": hashlib.sha256(body).hexdigest()})
    (tmp_path / f"{pack.id}-{pack.sha256}.apk").write_bytes(body)
    adb = InstallAdb()
    assert install_iconpack(adb, pack, cache=tmp_path) == "Success"
    assert adb.package_present(pack.package)
