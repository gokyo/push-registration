Find Push Registration records
----
  Find tokens for associated authId.

* **URL**

  `/push/register/:id`

* **Method:**

  `GET`

* **Response:**

    Example JSON response.

```json
[
  {
    "token": "some-token1",
    "device": {
      "os": "android",
      "osVersion": "6.0",
      "appVersion": "1.2",
      "model": "some-device-a"
    }
  },
  {
    "token": "another-token",
    "device": {
      "os": "android",
      "osVersion": "7.0",
      "appVersion": "1.2",
      "model": "some-device-b"
    }
  }
]
```

*  **URL Params**

   **None:**
 
* **Success Response:**
  * **Code:** 200 <br />

* **Error Response:**

  * **Code:** 404 NOT_FOUND <br />

  * **Code:** 401 UNAUTHORIZED <br />
    **Content:** `{"code":"UNAUTHORIZED","message":"Bearer token is missing or not authorized for access"}`

  * **Code:** 403 FORBIDDEN <br />
    **Content:** `{"code":"FORBIDDEN","message":"Access denied"}`

  OR on failure.

  * **Code:** 500 INTERNAL_SERVER_ERROR <br />


