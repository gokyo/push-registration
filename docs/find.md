Find latest Push Registration record
----
  Find deviceId and token for associated authId.

* **URL**

  `/push/register/:id`

* **Method:**

  `GET`

* **Response:**

    Example JSON response.

```json
{
  "deviceId": "some-device-id",
  "token": "some-token"
}
```

*  **URL Params**

   **None:**
 
* **Success Response:**
  * **Code:** 200 <br />

* **Error Response:**

  * **Code:** 401 UNAUTHORIZED <br />
    **Content:** `{"code":"UNAUTHORIZED","message":"Bearer token is missing or not authorized for access"}`

  OR on failure.

  * **Code:** 500 INTERNAL_SERVER_ERROR <br />


