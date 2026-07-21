"""
delamain Gateway - Pagination Utilities
"""

import json
import logging
from typing import Any, Callable, Dict, List, Union

logger = logging.getLogger("delamain.pagination_utils")


class PaginationUtils:
    """Utility class for handling pagination across different MCP tools."""

    DEFAULT_PAGE_SIZE = 100
    MAX_PAGE_SIZE = 10000
    MAX_OFFSET = 1000000

    @staticmethod
    def validate_pagination_params(offset: int, count: int) -> tuple[int, int]:
        if offset < 0:
            raise ValueError(f"offset must be non-negative, got: {offset}")
        if count < 0:
            raise ValueError(f"count must be non-negative, got: {count}")
        offset = min(offset, PaginationUtils.MAX_OFFSET)
        count = min(count, PaginationUtils.MAX_PAGE_SIZE)
        return offset, count

    @staticmethod
    async def get_paginated_data(
        endpoint: str,
        offset: int = 0,
        count: int = 0,
        additional_params: dict = None,
        data_extractor: Callable[[Any], List[Any]] = None,
        item_transformer: Callable[[Any], Any] = None,
        fetch_function: Callable = None,
    ) -> Union[Dict[str, Any], str]:
        try:
            offset, count = PaginationUtils.validate_pagination_params(offset, count)
        except ValueError as e:
            return {"error": f"Invalid pagination parameters: {str(e)}"}

        params = {"offset": offset}
        if count > 0:
            params["limit"] = count
        if additional_params:
            params.update(additional_params)

        try:
            if fetch_function is None:
                raise ValueError("fetch_function must be provided")

            response = await fetch_function(endpoint, params)

            try:
                status = response.get("status")
                if status in ("loading", "loading_started"):
                    return {
                        "type": response.get("type", "loading"),
                        "status": status,
                        "message": response.get("message", "Resource is loading in background"),
                        "retry_after": response.get("retry_after", 10),
                        "items": [],
                        "pagination": {"total": 0, "offset": 0, "limit": 0, "count": 0, "has_more": False},
                    }

                if "error" in response:
                    return {
                        "type": response.get("type", "error"),
                        "error": response.get("error"),
                        "suggestion": response.get("suggestion"),
                        "items": [],
                        "pagination": {"total": 0, "offset": 0, "limit": 0, "count": 0, "has_more": False},
                    }

                if data_extractor:
                    items = data_extractor(response)
                else:
                    items = (
                        response.get("classes")
                        or response.get("methods")
                        or response.get("fields")
                        or response.get("items", [])
                    )

                if item_transformer and items:
                    items = [item_transformer(item) for item in items]

                return PaginationUtils._build_standardized_response(response, items)

            except json.JSONDecodeError as e:
                logger.error(f"Failed to parse JSON response from JADX: {type(e).__name__}: {e}")
                return {"error": f"Invalid JSON response from JADX server: {type(e).__name__}"}

        except Exception as e:
            logger.error(f"Error in paginated request to {endpoint}: {type(e).__name__}: {e}")
            return {"error": f"Failed to fetch data from {endpoint}: {type(e).__name__}"}

    @staticmethod
    def _build_standardized_response(parsed_response: dict, items: List[Any]) -> dict:
        pagination_info = parsed_response.get("pagination", {})
        result = {
            "type": parsed_response.get("type", "paginated-list"),
            "items": items,
            "pagination": {
                "total": pagination_info.get("total", len(items)),
                "offset": pagination_info.get("offset", 0),
                "limit": pagination_info.get("limit", 0),
                "count": pagination_info.get("count", len(items)),
                "has_more": pagination_info.get("has_more", False),
            },
        }
        if "next_offset" in pagination_info:
            result["pagination"]["next_offset"] = pagination_info["next_offset"]
        if "prev_offset" in pagination_info:
            result["pagination"]["prev_offset"] = pagination_info["prev_offset"]
        if "current_page" in pagination_info:
            result["pagination"]["current_page"] = pagination_info["current_page"]
            result["pagination"]["total_pages"] = pagination_info.get("total_pages", 1)
            result["pagination"]["page_size"] = pagination_info.get("page_size", 0)
        return result
