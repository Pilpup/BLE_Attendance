"""
Multi-provider book metadata lookup service.
Queries Google Books API, OpenLibrary API, and ISBNdb API to resolve book details
from an ISBN barcode scan.
"""
from __future__ import annotations

import logging
import re
from typing import Optional
import httpx

from app.config import settings
from app.schemas.library import BookLookupResponse

logger = logging.getLogger("uvicorn.error")


def _normalize_isbn(raw: str) -> str:
    """Strip spaces, hyphens, and uppercase."""
    return re.sub(r"[^0-9X]", "", raw.strip().upper())


def _clean_text(text: Optional[str]) -> Optional[str]:
    if not text:
        return None
    # Strip HTML tags if present (Google Books volume descriptions often contain HTML)
    clean = re.sub(r"<[^>]+>", "", text).strip()
    return clean if clean else None


def _isbn13_to_10(isbn13: str) -> Optional[str]:
    if len(isbn13) != 13 or not isbn13.startswith("978"):
        return None
    core = isbn13[3:12]
    try:
        s = sum(int(core[i]) * (10 - i) for i in range(9))
        rem = (11 - (s % 11)) % 11
        return core + ("X" if rem == 10 else str(rem))
    except ValueError:
        return None


def _extract_toc(toc_raw) -> Optional[str]:
    if not toc_raw:
        return None
    if isinstance(toc_raw, list):
        lines = []
        for item in toc_raw:
            if isinstance(item, dict):
                label = str(item.get("label", "")).strip()
                title = str(item.get("title", "")).strip()
                page = str(item.get("pagenum", "")).strip()
                line = f"{label}: {title}".strip() if label and title else (title or label)
                if page:
                    line = f"{line} (p. {page})".strip()
                if line:
                    lines.append(line)
            elif isinstance(item, str) and item.strip():
                lines.append(item.strip())
        return "\n".join(lines) if lines else None
    elif isinstance(toc_raw, str) and toc_raw.strip():
        text = toc_raw.strip()
        if " -- " in text and "\n" not in text:
            items = [s.strip() for s in text.split(" -- ") if s.strip()]
            return "\n".join(items) if items else None
        return text
    return None


async def query_google_books(isbn: str, client: httpx.AsyncClient) -> Optional[dict]:
    params = {"q": f"isbn:{isbn}"}
    api_key = getattr(settings, "GOOGLE_BOOKS_API_KEY", "")
    if api_key:
        params["key"] = api_key
    try:
        resp = await client.get(
            "https://www.googleapis.com/books/v1/volumes",
            params=params,
            timeout=8.0,
        )
        if resp.status_code == 200:
            data = resp.json()
            items = data.get("items", [])
            if items:
                v = items[0].get("volumeInfo", {})
                title = v.get("title", "")
                if v.get("subtitle"):
                    title = f"{title}: {v['subtitle']}"
                authors = ", ".join(v.get("authors", []))
                categories = v.get("categories", [])
                category = categories[0].upper() if categories else "GENERAL"
                overview = _clean_text(v.get("description"))
                edition = None
                if v.get("publishedDate"):
                    edition = f"Published {v['publishedDate']}"
                table_of_contents = _extract_toc(v.get("tableOfContents"))
                return {
                    "title": title,
                    "author": authors,
                    "edition": edition,
                    "category": category,
                    "overview": overview,
                    "table_of_contents": table_of_contents,
                    "index": "Main Hall",
                    "source": "Google Books",
                }
    except Exception as e:
        logger.debug(f"Google Books API lookup failed for ISBN {isbn}: {e}")
    return None


async def query_open_library(isbn: str, client: httpx.AsyncClient) -> Optional[dict]:
    url = "https://openlibrary.org/api/books"
    params = {
        "bibkeys": f"ISBN:{isbn}",
        "format": "json",
        "jscmd": "data",
    }
    headers = {"User-Agent": "BLE-Attendance-Library/1.0 (academic-project)"}
    try:
        resp = await client.get(url, params=params, headers=headers, timeout=8.0)
        if resp.status_code == 200:
            payload = resp.json()
            data = payload.get(f"ISBN:{isbn}")
            if data and data.get("title"):
                title = data.get("title", "")
                if data.get("subtitle"):
                    title = f"{title}: {data['subtitle']}"
                authors = ", ".join(a.get("name", "") for a in data.get("authors", []))
                subjects = data.get("subjects", [])
                category = subjects[0].get("name", "GENERAL").upper() if subjects else "GENERAL"
                overview = None
                notes = data.get("notes")
                if isinstance(notes, dict):
                    overview = notes.get("value")
                elif isinstance(notes, str):
                    overview = notes
                if not overview and data.get("excerpts"):
                    overview = data.get("excerpts")[0].get("text")
                edition = data.get("publish_date") or data.get("by_statement")
                classifications = data.get("classifications", {})
                index = "Main Hall"
                if classifications.get("lc_classifications"):
                    index = classifications["lc_classifications"][0]
                elif classifications.get("dewey_decimal_class"):
                    index = classifications["dewey_decimal_class"][0]
                table_of_contents = _extract_toc(data.get("table_of_contents"))

                # Fallback to direct /isbn/{isbn}.json if jscmd=data lacked table_of_contents
                if not table_of_contents:
                    isbns_to_check = [isbn]
                    isbn10 = _isbn13_to_10(isbn)
                    if isbn10:
                        isbns_to_check.append(isbn10)
                    for cand in isbns_to_check:
                        try:
                            cand_resp = await client.get(
                                f"https://openlibrary.org/isbn/{cand}.json",
                                headers=headers,
                                timeout=6.0,
                                follow_redirects=True,
                            )
                            if cand_resp.status_code == 200:
                                cand_data = cand_resp.json()
                                toc_cand = _extract_toc(cand_data.get("table_of_contents"))
                                if toc_cand:
                                    table_of_contents = toc_cand
                                    break
                        except Exception:
                            pass

                return {
                    "title": title,
                    "author": authors,
                    "edition": str(edition) if edition else None,
                    "category": category,
                    "overview": _clean_text(overview),
                    "table_of_contents": table_of_contents,
                    "index": index,
                    "source": "OpenLibrary",
                }
    except Exception as e:
        logger.debug(f"OpenLibrary API lookup failed for ISBN {isbn}: {e}")
    return None


async def query_isbndb(isbn: str, client: httpx.AsyncClient) -> Optional[dict]:
    api_key = getattr(settings, "ISBNDB_API_KEY", "")
    if not api_key:
        return None
    url = f"https://api2.isbndb.com/book/{isbn}"
    headers = {"Authorization": api_key, "User-Agent": "BLE-Attendance-Library/1.0"}
    try:
        resp = await client.get(url, headers=headers, timeout=8.0)
        if resp.status_code == 200:
            b = resp.json().get("book", {})
            title = b.get("title", "")
            authors = ", ".join(b.get("authors", []))
            edition = b.get("edition")
            overview = _clean_text(b.get("synopsis") or b.get("overview"))
            subjects = b.get("subjects", [])
            category = subjects[0].upper() if subjects else "GENERAL"
            index = b.get("dewey_decimal") or "Main Hall"
            table_of_contents = _extract_toc(b.get("table_of_contents") or b.get("toc"))
            return {
                "title": title,
                "author": authors,
                "edition": str(edition) if edition else None,
                "category": category,
                "overview": overview,
                "table_of_contents": table_of_contents,
                "index": index,
                "source": "ISBNdb",
            }
    except Exception as e:
        logger.debug(f"ISBNdb API lookup failed for ISBN {isbn}: {e}")
    return None


async def lookup_book_by_isbn(isbn: str) -> Optional[BookLookupResponse]:
    """
    Cascading book metadata lookup across Google Books API, OpenLibrary API,
    and ISBNdb API. Merges metadata if one provider has description/overview
    and another has classification/edition.
    """
    clean_isbn = _normalize_isbn(isbn)
    if not clean_isbn:
        return None

    async with httpx.AsyncClient() as client:
        # Query providers
        gb_data = await query_google_books(clean_isbn, client)
        ol_data = await query_open_library(clean_isbn, client)
        isbndb_data = await query_isbndb(clean_isbn, client)

    sources = [s for s in (gb_data, ol_data, isbndb_data) if s]
    if not sources:
        return None

    primary = sources[0]
    title = primary["title"]
    author = primary["author"]
    edition = primary["edition"]
    category = primary["category"]
    overview = primary["overview"]
    table_of_contents = primary.get("table_of_contents")
    index = primary["index"]
    source = primary["source"]

    # Fill in missing fields from remaining sources
    for secondary in sources[1:]:
        if not title and secondary.get("title"):
            title = secondary["title"]
        if not author and secondary.get("author"):
            author = secondary["author"]
        if not edition and secondary.get("edition"):
            edition = secondary["edition"]
        if (not category or category == "GENERAL") and secondary.get("category"):
            category = secondary["category"]
        if not overview and secondary.get("overview"):
            overview = secondary["overview"]
        if not table_of_contents and secondary.get("table_of_contents"):
            table_of_contents = secondary["table_of_contents"]
        if (not index or index == "Main Hall") and secondary.get("index"):
            index = secondary["index"]
        source += f" + {secondary['source']}"

    if category:
        category = category[:50].strip()

    return BookLookupResponse(
        title=title or "Unknown Title",
        author=author or "Unknown Author",
        edition=edition,
        index=index or "Main Hall",
        isbn=clean_isbn,
        category=category or "GENERAL",
        overview=overview,
        table_of_contents=table_of_contents,
        source=source,
    )
